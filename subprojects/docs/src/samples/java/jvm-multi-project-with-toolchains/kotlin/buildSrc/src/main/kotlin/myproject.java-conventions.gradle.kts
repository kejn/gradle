plugins {
    java
}

version = "1.0.2"
group = "com.example"

repositories {
    jcenter()
}

// tag::toolchain[]
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(14))
    }
}
// end::toolchain[]

tasks.test {
    useJUnitPlatform()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.7.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
}
