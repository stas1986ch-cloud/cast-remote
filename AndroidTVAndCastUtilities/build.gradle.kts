// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
}

// Avoid Windows OneDrive file-sync locks on Gradle build intermediates.
if (rootDir.absolutePath.lowercase().contains("onedrive")) {
    allprojects {
        layout.buildDirectory.set(file("${System.getProperty("java.io.tmpdir")}/androidtv-cast-utilities-build/${project.name}"))
    }
}