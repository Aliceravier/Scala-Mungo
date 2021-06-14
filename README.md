# Papaya
*Prototype implementation of a typestate checking plugin for Scala.*

## Tutorial
View tutorial at https://aliceravier.github.io/


## Build instructions

First clone the repository 
```bash
git clone https://github.com/aliceravier/Scala-Mungo
cd Scala-Mungo/Scala-Mungo-Prototype
```

Then build the jar file:

```bash
# Windows
makejar.bat

# Unix
make
```
A jar file called `Scala-Mungo-Prototype_2.13.jar` should have been generated in the `Scala-Mungo/Scala-Mungo-Prototype` directory.

Add these lines to your build.sbt of the project you want to use Papaya in:
```
scalacOptions += "-Xplugin:/path/to/jar/file.jar"

val root_path = "/absolute/path/to/current/project"
scalacOptions += "-P:GetFileFromAnnotation:" + root_path
```

Follow the [tutorial](https://aliceravier.github.io/) to see how to use the plugin.
