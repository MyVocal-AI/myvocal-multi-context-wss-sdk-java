# Runtime dependencies

The official ZIP contains a Java 8-compatible thin JAR and these runtime
dependencies under `lib/`:

- `com.squareup.okhttp3:okhttp:3.14.9`
- `com.squareup.okio:okio:1.17.2`
- `org.json:json:20231013`

When using Maven or Gradle, the package `pom.xml` declares these dependencies.
When compiling directly with `javac`, include all JAR files under `lib/` on the
classpath.
