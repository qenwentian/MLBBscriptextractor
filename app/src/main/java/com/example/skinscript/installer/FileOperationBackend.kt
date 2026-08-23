package com.example.skinscript.installer

import java.io.InputStream

interface FileOperationBackend {
    suspend fun exists(path: String): Boolean
    suspend fun isDirectory(path: String): Boolean
    suspend fun createDirectory(path: String): Boolean
    suspend fun isWritable(path: String): Boolean
    suspend fun delete(path: String): Boolean
    suspend fun copyFile(source: InputStream, destination: String): Boolean
    suspend fun checkExistingFiles(paths: List<String>): Set<String>
    suspend fun createDirectories(paths: List<String>): Boolean
    fun createTarProcess(destination: String): Process?
}