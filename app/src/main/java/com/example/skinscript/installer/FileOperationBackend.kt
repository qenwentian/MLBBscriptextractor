package com.example.skinscript.installer

import java.io.InputStream

interface FileOperationBackend {
    suspend fun exists(path: String): Boolean
    suspend fun isDirectory(path: String): Boolean
    suspend fun createDirectory(path: String): Boolean
    suspend fun isWritable(path: String): Boolean
    suspend fun delete(path: String): Boolean
    suspend fun copyFile(source: InputStream, destination: String): Boolean
}