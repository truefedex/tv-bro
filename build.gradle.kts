// Top-level build file where you can add configuration options common to all sub-projects/modules.

import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import org.w3c.dom.Document
import java.io.BufferedReader
import java.io.FileOutputStream
import java.net.URL
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import java.util.Date
import java.text.SimpleDateFormat


buildscript {
    val kotlin_version = "1.4.10"
    repositories {
        google()
        jcenter()
        maven {
            setUrl("https://maven.fabric.io/public")
        }
    }
    dependencies {
        classpath("com.android.tools.build:gradle:4.0.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlin_version")
        classpath("org.jetbrains.kotlin:kotlin-android-extensions:$kotlin_version")
        classpath("com.google.gms:google-services:4.3.4")
        classpath("com.google.firebase:firebase-crashlytics-gradle:2.3.0")

        // NOTE: Do not place your application dependencies here; they belong
        // in the individual module build.gradle files
    }
}

allprojects {
    repositories {
        google()
        jcenter()
        maven { setUrl("https://jitpack.io") }
    }
}

tasks.register("clean", Delete::class){
    delete(rootProject.buildDir)
}

tasks.register("prepare adblocker xml"){
    val pglYoyoListUrl = "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=nohtml&showintro=0&mimetype=plaintext"
    val hosts = URL(pglYoyoListUrl).openStream().bufferedReader().use(BufferedReader::readLines)
    val xmlFile = FileOutputStream("$projectDir/app/src/main/assets/adblockerlist.xml")

    try {
        val documentFactory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance()
        val documentBuilder: DocumentBuilder = documentFactory.newDocumentBuilder()
        val document: Document = documentBuilder.newDocument()

        // root element
        val root = document.createElement("root")
        var attr = document.createAttribute("date")
        attr.value = SimpleDateFormat("dd-MM-yyyy HH:mm:ss.SSSZ").format(Date())
        root.setAttributeNode(attr)
        document.appendChild(root)

        hosts.forEach {
            val employee = document.createElement("item")
            employee.textContent = it

            attr = document.createAttribute("type")
            attr.value = "host"
            employee.setAttributeNode(attr)

            attr = document.createAttribute("src")
            attr.value = "pgl.yoyo"
            employee.setAttributeNode(attr)

            root.appendChild(employee)
        }
        //transform the DOM Object to an XML File
        val transformerFactory: TransformerFactory = TransformerFactory.newInstance()
        val transformer = transformerFactory.newTransformer()
        val domSource = DOMSource(document)
        val streamResult = StreamResult(xmlFile)

        transformer.transform(domSource, streamResult)
        println("Done creating XML File")
    } catch (e: Exception) {
        e.printStackTrace()
    }
}