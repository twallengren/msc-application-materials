# MSc application materials

Technical case studies and supporting evidence for an MSc application.

- **[Java IRR case study](projects/irr-java/README.md):** a Java 21 numerical library, correctness tests, reproducible JMH experiments, and a LaTeX report.
- **Home network:** planned case study; future work.
- **Concordia (tentative):** possible case study; scope and evidence remain to be established.

## Open in IntelliJ IDEA

Open the repository's **`pom.xml` as a Maven project**, using JDK 21. The root Maven reactor imports the IRR parent plus `irr-core` and `irr-benchmarks`, including `src/main/java`, `src/test/java`, dependencies, and generated benchmark sources. If the folder is already open, right-click the root `pom.xml` and choose **Add as Maven Project**, then **Reload All Maven Projects** in the Maven tool window and let indexing finish. Merely assigning a JDK to a plain module does not import Maven source roots or dependencies. See [JetBrains' Maven import instructions](https://www.jetbrains.com/help/idea/import-project-or-module-wizard.html).

```sh
./mvnw clean verify
./mvnw -f projects/irr-java/pom.xml spotless:apply
```

The build checks Java formatting with pinned Spotless and google-java-format versions. `.editorconfig` provides consistent editor indentation; IntelliJ's local `.idea` state is not shared through Git. The project can also be opened directly through `projects/irr-java/pom.xml`.
