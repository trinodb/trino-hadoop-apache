/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.hadoop;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

@Test(groups = "docker")
public class HdfsEncryptionIT
{
    private static final String SERVER_DATA = "Server-created encrypted HDFS data\n".repeat(5_000);
    private static final String CLIENT_DATA = "Client-written encrypted HDFS data\n".repeat(5_000);

    @Test
    public void testEncryption()
            throws Exception
    {
        try (GenericContainer<?> hadoop = new GenericContainer<>("apache/hadoop:3.4.3")
                .withCreateContainerCmdModifier(command -> command.withEntrypoint("/bin/bash"))
                .withCommand("-c", "sleep infinity")
                .withEnv("HADOOP_LOG_DIR", "/tmp/hadoop-logs")
                .withEnv("HADOOP_PID_DIR", "/tmp/hadoop-pids")
                .withExposedPorts(9000)
                .waitingFor(Wait.forSuccessfulCommand("test -d /opt/hadoop"))) {
            hadoop.start();

            String containerIp = hadoop.getContainerInfo().getNetworkSettings().getNetworks().values().iterator().next().getIpAddress();
            serverConfiguration(hadoop, "core-site.xml", Map.of(
                    "fs.defaultFS", "hdfs://localhost:9000",
                    "hadoop.security.key.provider.path", "kms://http@localhost:9600/kms"));
            serverConfiguration(hadoop, "hdfs-site.xml", Map.of(
                    "dfs.namenode.rpc-address", "0.0.0.0:9000",
                    "dfs.namenode.name.dir", "file:///tmp/hdfs-name",
                    "dfs.datanode.data.dir", "file:///tmp/hdfs-data",
                    "dfs.permissions.enabled", "false",
                    "dfs.replication", "1",
                    "dfs.namenode.redundancy.considerLoad", "false",
                    "dfs.encryption.key.provider.uri", "kms://http@%s:9600/kms".formatted(containerIp)));
            serverConfiguration(hadoop, "kms-site.xml", Map.of(
                    "hadoop.kms.key.provider.uri", "jceks://file@/tmp/kms-keys.jceks",
                    "hadoop.kms.authentication.type", "simple",
                    "hadoop.kms.authentication.simple.anonymous.allowed", "true"));
            hadoop.copyFileToContainer(Transferable.of(SERVER_DATA), "/tmp/server-data");
            hadoop.copyFileToContainer(Transferable.of(CLIENT_DATA), "/tmp/client-data");
            serverCommand(hadoop, "hdfs", "namenode", "-format", "-force", "-nonInteractive");
            serverCommand(hadoop, "hadoop", "--daemon", "start", "kms");
            serverCommand(hadoop, "hdfs", "--daemon", "start", "namenode");
            serverCommand(hadoop, "hdfs", "--daemon", "start", "datanode");
            Wait.forSuccessfulCommand("hdfs dfsadmin -report -live | grep -q 'Live datanodes (1)'")
                    .withStartupTimeout(Duration.ofMinutes(2))
                    .waitUntilReady(hadoop);

            serverCommand(hadoop, "hdfs", "dfs", "-mkdir", "/plain");
            serverCommand(hadoop, "hdfs", "dfs", "-put", "/tmp/server-data", "/plain/input");
            createEncryptionZone(hadoop, "aes128", 128);
            createEncryptionZone(hadoop, "aes256", 256);

            // Use the packaged native library and default client configuration, as Trino does.
            // The NameNode supplies the file cipher and KMS URI through HDFS.
            assertTrue(HadoopNative.class.getProtectionDomain().getCodeSource().getLocation().getPath().endsWith(".jar"));
            HadoopNative.requireHadoopNative();
            try (FileSystem fileSystem = FileSystem.get(URI.create("hdfs://%s:%s/".formatted(hadoop.getHost(), hadoop.getMappedPort(9000))), new Configuration())) {
                for (String directory : List.of("plain", "aes128", "aes256")) {
                    testReadServerFile(fileSystem, directory);
                    testWriteClientFile(hadoop, fileSystem, directory);
                }
            }
        }
    }

    private static void createEncryptionZone(GenericContainer<?> hadoop, String directory, int keyLength)
            throws Exception
    {
        serverCommand(hadoop, "hadoop", "key", "create", directory, "-cipher", "AES/CTR/NoPadding", "-size", Integer.toString(keyLength));
        serverCommand(hadoop, "hdfs", "dfs", "-mkdir", "/" + directory);
        serverCommand(hadoop, "hdfs", "crypto", "-createZone", "-keyName", directory, "-path", "/" + directory);
        serverCommand(hadoop, "hdfs", "dfs", "-put", "/tmp/server-data", "/" + directory + "/input");
        assertTrue(serverCommand(hadoop, "hdfs", "crypto", "-getFileEncryptionInfo", "-path", "/" + directory + "/input").contains("AES/CTR/NoPadding"));
        verifyServerFile(hadoop, "/" + directory + "/input", "/tmp/server-data");
    }

    private static void testReadServerFile(FileSystem fileSystem, String directory)
            throws IOException
    {
        try (var input = fileSystem.open(new Path("/" + directory + "/input"))) {
            assertEquals(new String(input.readAllBytes(), UTF_8), SERVER_DATA);
            input.seek(31);
            assertEquals(new String(input.readNBytes(1_000), UTF_8), SERVER_DATA.substring(31, 1_031));
        }
    }

    private static void testWriteClientFile(GenericContainer<?> hadoop, FileSystem fileSystem, String directory)
            throws Exception
    {
        String path = "/" + directory + "/client-output";
        try (var output = fileSystem.create(new Path(path), false)) {
            output.write(CLIENT_DATA.getBytes(UTF_8));
        }
        verifyServerFile(hadoop, path, "/tmp/client-data");
    }

    private static void verifyServerFile(GenericContainer<?> hadoop, String path, String expectedFile)
            throws Exception
    {
        String localFile = "/tmp/read-back" + path.replace('/', '-');
        serverCommand(hadoop, "hdfs", "dfs", "-get", "-f", path, localFile);
        serverCommand(hadoop, "cmp", localFile, expectedFile);
    }

    private static void serverConfiguration(GenericContainer<?> hadoop, String filename, Map<String, String> properties)
    {
        String xml = properties.entrySet().stream()
                .map(entry -> "<property><name>%s</name><value>%s</value></property>".formatted(entry.getKey(), entry.getValue()))
                .collect(joining("\n", "<configuration>\n", "\n</configuration>\n"));
        hadoop.copyFileToContainer(Transferable.of(xml), "/opt/hadoop/etc/hadoop/" + filename);
    }

    private static String serverCommand(GenericContainer<?> hadoop, String... command)
            throws Exception
    {
        var result = hadoop.execInContainer(command);
        assertEquals(result.getExitCode(), 0, "Server command %s failed:%n%s%n%s".formatted(String.join(" ", command), result.getStdout(), result.getStderr()));
        return result.getStdout();
    }
}
