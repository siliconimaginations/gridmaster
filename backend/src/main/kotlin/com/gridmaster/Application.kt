package com.gridmaster

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class GridMasterApplication

fun main(args: Array<String>) {
    runApplication<GridMasterApplication>(*args)
}
