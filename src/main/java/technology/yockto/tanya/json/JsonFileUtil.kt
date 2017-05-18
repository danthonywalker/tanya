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

fun <T : Any> getNullableJsonFile(type: KClass<T>): T? {
    type.takeUnless { jsonFiles.contains(type) }?.let(::reloadJsonFile)

    @Suppress("UNCHECKED_CAST")
    return jsonFiles[type] as T?
}

fun reloadJsonFile(type: KClass<*>) {
    val jsonFileAnnotation = JsonFile::class
    val jsonFile = type.annotations.find { it.annotationClass == jsonFileAnnotation }

    if(jsonFile == null) { //If a @JsonFile annotation is absent then don't attempt to process
        throw IllegalStateException("$type does not contain a $jsonFileAnnotation annotation")

    } else { //Guaranteed JsonFile.
        @Suppress("NAME_SHADOWING")
        val jsonFile = jsonFile as JsonFile
        val sourcePath = "${jsonFile.source}${jsonFile.fileName}"
        val destination = Paths.get("${jsonFile.destination}${jsonFile.fileName}")

        val source = jsonFile.takeUnless { it.sourceType == NONE }?.let {
            if(it.sourceType == EXTERNAL) {
                FileInputStream(sourcePath)

            } else { //Use class's path as relative point
                type.java.getResourceAsStream(sourcePath)
            }
        }
        
        source?.takeIf { Files.notExists(destination) }?.let { Files.copy(it, destination) }
        jsonFiles.put(type, jsonConverter.readValue(destination.toFile(), type.java))
    }
}

fun reloadJsonFiles(): Unit = jsonFiles.keys.forEach(::reloadJsonFile)
fun <T : Any> getJsonFile(type: KClass<T>): T = getNullableJsonFile(type)!!