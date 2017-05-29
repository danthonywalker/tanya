/*
 * Tanya - Discord Bot
 * Copyright (C) 2017  Yockto Technologies
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package technology.yockto.tanya.json

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import technology.yockto.tanya.json.SourceType.EXTERNAL
import technology.yockto.tanya.json.SourceType.NONE
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

private val jsonFiles = ConcurrentHashMap<KClass<*>, Any?>()
private val jsonConverter = ObjectMapper().registerKotlinModule()

fun <T : Any> KClass<T>.getNullableJsonFile(): T? {
    takeUnless { jsonFiles.contains(it) }?.reloadJsonFile()

    @Suppress("UNCHECKED_CAST")
    return jsonFiles[this] as T?
}

fun KClass<*>.reloadJsonFile() {
    val jsonFileAnnotation = JsonFile::class
    val jsonFile = annotations.find { it.annotationClass == jsonFileAnnotation }

    if(jsonFile == null) { //If a @JsonFile annotation is absent then don't attempt to process
        throw IllegalStateException("$this does not contain a $jsonFileAnnotation annotation")

    } else { //Guaranteed JsonFile.
        @Suppress("NAME_SHADOWING")
        val jsonFile = jsonFile as JsonFile
        val sourcePath = "${jsonFile.source}${jsonFile.fileName}"
        val destination = Paths.get("${jsonFile.destination}${jsonFile.fileName}")

        val source = jsonFile.takeUnless { it.sourceType == NONE }?.let {
            if(it.sourceType == EXTERNAL) {
                FileInputStream(sourcePath)

            } else { //Use class as a relative point
                java.getResourceAsStream(sourcePath)
            }
        }
        
        source?.takeIf { Files.notExists(destination) }?.let { Files.copy(it, destination) }
        jsonFiles.put(this, jsonConverter.readValue(destination.toFile(), java))
    }
}

fun reloadJsonFiles(): Unit = jsonFiles.keys.forEach { it.reloadJsonFile() }
fun <T : Any> KClass<T>.getJsonFile(): T = getNullableJsonFile()!!