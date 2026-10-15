plugins {
    id("notdisk.kotlin.jvm")
}

dependencies {
    api(project(":core:provider-api"))
    api(project(":core:model"))
    api(project(":core:common"))
}
