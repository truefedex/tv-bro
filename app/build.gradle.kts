import org.w3c.dom.Document
import java.io.BufferedReader
import java.io.FileOutputStream
import java.net.URL
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.*
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult


plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("android.extensions")
    kotlin("kapt")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

val properties = Properties()
val localPropertiesFile: File = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { properties.load(it) }
}

android {
    compileSdkVersion(29)
    buildToolsVersion = "29.0.3"

    defaultConfig {
        applicationId = "com.phlox.tvwebbrowser"
        minSdkVersion(21)
        targetSdkVersion(29)
        versionCode = 43
        versionName = "1.6.0"

        javaCompileOptions {
            annotationProcessorOptions {
                argument("room.incremental", "true")
            }
        }
    }
    signingConfigs {
        create("release") {
            storeFile = rootProject.file(properties.getProperty("storeFile"))
            storePassword = properties.getProperty("storePassword")
            keyAlias = properties.getProperty("keyAlias")
            keyPassword = properties.getProperty("keyPassword")
        }
    }
    buildTypes {
        getByName("release") {
            isDebuggable = true
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig=signingConfigs.getByName("release")
        }
    }

    flavorDimensions("crashlytics", "appstore")
    productFlavors {
        create("crashlyticsfree") {
            dimension("crashlytics")
        }
        create("crashlytics") {
            dimension("crashlytics")
            //versionNameSuffix "-crashlytics"
        }

        create("google") {
            dimension("appstore")
        }
        create("amazon") {
            dimension("appstore")
        }
    }

    lintOptions {
        isAbortOnError=false
    }

    kapt {
        arguments {
            //used when AppDatabase @Database annotation exportSchema = true. Useful for migrations
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    implementation("androidx.appcompat:appcompat:1.2.0")
    implementation("androidx.constraintlayout:constraintlayout:2.0.4")

    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.4.30")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.4.1")

    implementation("androidx.lifecycle:lifecycle-extensions:2.2.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.3.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.3.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.3.0")

    val roomVersion = "2.2.5"
    implementation("androidx.room:room-runtime:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")

    implementation("com.github.truefedex:segmented-button:v1.0.0")
    implementation("de.halfbit:pinned-section-listview:1.0.0")

    "crashlyticsImplementation"("com.google.firebase:firebase-core:18.0.2")
    "crashlyticsImplementation"("com.google.firebase:firebase-crashlytics-ktx:17.3.1")
}

tasks.getByName("check").dependsOn("lint")

tasks.register("prepareAdblockerXml"){
    doLast {
        val pglYoyoListUrl = "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=nohtml&showintro=0&mimetype=plaintext"
        //for unknown reason on some machines there ssl errors while accessing pgl.yoyo.org
        val trustAllCerts: Array<javax.net.ssl.TrustManager> = arrayOf(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(certs: Array<X509Certificate?>?, authType: String?) {}
            override fun checkServerTrusted(certs: Array<X509Certificate?>?, authType: String?) {}

            override fun getAcceptedIssuers(): Array<X509Certificate>? {
                return null
            }
        })
        val sc = javax.net.ssl.SSLContext.getInstance("SSL")
        sc.init(null, trustAllCerts, null)
        javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)
        javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
        val hosts = URL(pglYoyoListUrl).openStream().bufferedReader().use(BufferedReader::readLines)
        val xmlFile = FileOutputStream("$projectDir/src/main/assets/adblockerlist.xml")

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
}