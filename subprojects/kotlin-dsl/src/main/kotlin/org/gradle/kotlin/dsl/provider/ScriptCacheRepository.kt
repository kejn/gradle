/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.kotlin.dsl.provider

import org.gradle.cache.CacheBuilder
import org.gradle.cache.CacheRepository
import java.io.File


const val SCRIPT_CACHE_BASE_DIR_OVERRIDE_PROPERTY = "org.gradle.cache.internal.kotlinScriptCacheBaseDir"


internal
class ScriptCacheRepository(
    private val cacheRepository: CacheRepository
) {

    fun cache(key: String): CacheBuilder {
        val baseDirOverride = System.getProperty(SCRIPT_CACHE_BASE_DIR_OVERRIDE_PROPERTY)
        if (baseDirOverride != null) {
            return cacheRepository.cache(File(baseDirOverride).resolve(key))
        }
        return cacheRepository.cache(key)
    }
}
