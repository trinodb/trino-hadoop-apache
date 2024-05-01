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

import com.google.common.reflect.Reflection;
import org.apache.hadoop.io.compress.zstd.ZStandardCompressor;
import org.apache.hadoop.io.compress.zstd.ZStandardDecompressor;
import org.apache.hadoop.util.NativeCodeLoader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class HadoopNative
{
    private static boolean loaded = false;
    private static Throwable error = null;

    private HadoopNative() {}

    public static synchronized void requireHadoopNative()
    {
        if (loaded) {
            return;
        }
        if (error != null) {
            throw new RuntimeException("failed to load Hadoop native library", error);
        }
        try {
            // force initialization early to avoid loading the native library
            Reflection.initialize(ZStandardCompressor.class);
            Reflection.initialize(ZStandardDecompressor.class);

            loadLibrary("hadoop");
            NativeCodeLoader.setNativeCodeLoaded();

            loaded = true;
        }
        catch (Throwable t) {
            error = t;
            throw new RuntimeException("failed to load Hadoop native library", error);
        }
    }

    private static void loadLibrary(String name)
            throws IOException
    {
        String libraryPath = getLibraryPath(name);
        URL url = HadoopNative.class.getResource(libraryPath);
        if (url == null) {
            throw new RuntimeException("library not found: " + libraryPath);
        }

        File file = File.createTempFile(name, null);
        file.deleteOnExit();
        try (InputStream in = url.openStream()) {
            Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        System.load(file.getAbsolutePath());
    }

    private static String getLibraryPath(String name)
    {
        return "/nativelib/" + getPlatform() + "/" + System.mapLibraryName(name);
    }

    private static String getPlatform()
    {
        String name = System.getProperty("os.name");
        String arch = System.getProperty("os.arch");
        return (name + "-" + arch).replace(' ', '_');
    }
}
