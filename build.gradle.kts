buildscript {
    dependencies {
        // LiteRT-LM 0.17.0-alpha1 is compiled with Kotlin 2.4 metadata.
        // AGP 9 uses built-in Kotlin, so lift its KGP runtime to a compatible compiler.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.4.0" apply false
}
