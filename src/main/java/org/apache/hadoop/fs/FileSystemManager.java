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
package org.apache.hadoop.fs;

import static java.util.Objects.requireNonNull;

public final class FileSystemManager
{
    private FileSystemManager() {}

    @SuppressWarnings("RedundantFieldInitialization")
    private static FileSystemCache existingCache = null;

    @SuppressWarnings("ObjectEquality")
    public static synchronized void registerCache(FileSystemCache cache)
    {
        requireNonNull(cache, "cache is null");
        if (existingCache == null) {
            FileSystem.CACHE = new ForwardingFileSystemCache(cache);
            existingCache = cache;
        }
        else if (existingCache != cache) {
            throw new IllegalStateException("FileSystem cache already registered");
        }
    }
}
