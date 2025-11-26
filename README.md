### 📢 DEPRECATED
[gradle-dotnet-plugin](https://github.com/Itiviti/gradle-dotnet-plugin) is now available for building projects via dotnet command line tool chains. The plugin supports running nunit, code coverage, nuget restore and push. 

# Gradle MsBuild Plugin

> **Note:** This is a fork of the original [gradle-msbuild-plugin](https://github.com/Itiviti/gradle-msbuild-plugin) by Itiviti, updated for **Gradle 8/9 compatibility** and published under the `de.inetsoftware` group ID.

> **Disclaimer:** Most of the changes in this fork, including Gradle 8/9 compatibility fixes and CI/CD pipeline improvements, were created with the assistance of Cursor AI. While the code has been tested and verified, please review changes carefully before using in production environments.

## Fork Information

This fork maintains **forward compatibility** with the original plugin while providing:
- **Gradle 8/9 compatibility** - Fixed deprecated API usage and compatibility issues
- **Updated group ID** - Published as `de.inetsoftware.gradle:gradle-msbuild-plugin`
- **Updated plugin IDs** - Use `de.inetsoftware.msbuild` instead of `com.ullink.msbuild`
- **Modern CI/CD pipeline** - GitHub Actions workflows replacing Travis CI and AppVeyor
- **All original functionality preserved** - Drop-in replacement for the original plugin

### Migration from Original Plugin

If you're using the original `com.ullink.gradle:gradle-msbuild-plugin`, you can migrate to this fork by:

1. **Update your buildscript dependency:**
   ```groovy
   buildscript {
       dependencies {
           classpath 'de.inetsoftware.gradle:gradle-msbuild-plugin:4.8'
       }
   }
   ```

2. **Update plugin application:**
   ```groovy
   apply plugin: 'de.inetsoftware.msbuild'
   // or
   plugins {
       id 'de.inetsoftware.msbuild' version '4.8'
   }
   ```

All task names and configuration remain the same - only the plugin ID and group ID have changed.

---

This plugin allows to compile an MsBuild project.
It also supports project file parsing, and some basic up-to-date checks to skip the build.
I doubt it'll work on anything else than .csproj files right now, but adding support for more will be easy.

Plugin applies the base plugin automatically, and hooks msbuild output folders into the clean task process.
Below tasks are provided by the plugin:

## Prerequisites
* .Net Framework 4.6

## msbuild

Prior to execution, this task will parse the provided project file and gather all its inputs (which are added to the task inputs):
- included files (Compile, EmbeddedResource, None, Content)
- ProjectReference (recursively gathers its inputs) // TODO: should use outputs instead ?
- References with a HintPath

OutputPath (e.g. bin/Debug) & Intermediary (e.g. obj/Debug) are set as output directories for the task.

To apply the plugin:

```groovy
// Starting from gradle 2.1
plugins {
  id 'de.inetsoftware.msbuild' version '4.8'
}
```

or
```groovy
buildscript {
    repositories {
        url "https://plugins.gradle.org/m2/"
    }

    dependencies {
        classpath 'de.inetsoftware.gradle:gradle-msbuild-plugin:4.8'
    }
}
apply plugin:'de.inetsoftware.msbuild'
```

and configure by:

```groovy
msbuild {
  // mandatory (one of those)
  solutionFile = 'my-solution.sln'
  projectFile = file('src/my-project.csproj')

  // MsBuild project name (/p:Project=...)
  projectName = project.name

  // Verbosity (/v:detailed, by default uses gradle logging level)
  verbosity = 'detailed'

  // targets to execute (/t:Clean;Rebuild, no default)
  targets = ['Clean', 'Rebuild']


  // MsBuild resolution
  // it support to search the msbuild tools from vswhere (by default it searches the latest)
  version = '15.0'
  // or define the exact msbuild dir explicity
  msbuildDir = 'C:\\Program Files (x86)\\Microsoft Visual Studio\\2017\\BuildTools\\MSBuild\\15.0\\bin'


  // Below values can override settings from the project file

  // overrides project OutputPath
  destinationDir = 'build/msbuild/bin'

  // overrides project IntermediaryOutputPath
  intermediateDir = 'build/msbuild/obj'

  // Generates XML documentation file (from javadoc through custom DocLet)
  generateDoc = false

  // Other msbuild options can be set:
  // loggerAssembly, generateDoc, debugType, optimize, debugSymbols, configuration, platform, defineConstants ...

  // you can also provide properties by name (/p:SomeProperty=Value)
  parameters.SomeProperty = 'Value'

  // Or, if you use built-in msbuild parameters that aren't directly available here,
  // you can take advantage of the ExtensionAware interface
  ext["flp1"] = "LogFile=" + file("${project.name}.errors.log").path + ";ErrorsOnly;Verbosity=diag"
}
```

## Accessing Build Artifacts

After the build completes, the plugin automatically collects all build artifacts (DLL, PDB, XML files) from the output directories. This works even when project parsing is skipped (e.g., for old .NET Framework projects).

### Using the `archive()` Method

The simplest way to access build artifacts is using the `archive()` method in your `artifacts` block:

```groovy
msbuild {
    // ... configuration ...
    
    doLast {
        artifacts {
            archives msbuild.archive('Reporting', 'dll')
            archives msbuild.archive('Reporting', 'pdb')
            archives msbuild.archive('Reporting', 'xml')
        }
    }
}
```

The `archive()` method signature:
- `archive(String projectName, String type = 'dll')` - Returns a `File` object
- **projectName**: The name of the project (e.g., 'Reporting', 'Tests', 'MyProject')
- **type**: The artifact type: `'dll'`, `'pdb'`, or `'xml'` (default: `'dll'`)

The method performs case-insensitive matching and partial name matching, so it will find projects even if the exact name doesn't match.

### Accessing All Artifacts

You can also access the full artifacts map directly:

```groovy
msbuild {
    doLast {
        def artifacts = msbuild.artifacts
        println "Available projects: ${artifacts.keySet()}"
        
        // Access specific project artifacts
        def reportingArtifacts = artifacts['Reporting']
        def dllFile = reportingArtifacts['dll']
        def pdbFile = reportingArtifacts['pdb']
        def xmlFile = reportingArtifacts['xml']
    }
}
```

The `artifacts` property returns a `Map<String, Map<String, File>>`:
- **Key**: Project name (e.g., 'Reporting', 'Tests')
- **Value**: Map with keys `'dll'`, `'pdb'`, `'xml'` pointing to `File` objects

### When Project Parsing is Skipped

For old .NET Framework projects (ToolsVersion 14.0 or earlier), project parsing may be automatically skipped because .NET SDK MSBuild cannot parse these projects. The artifact collection still works by scanning the actual build output directories, so you can always use `archive()` or `artifacts` even when `msbuild.projects` is empty.

**Note:** The `archive()` method must be called **after** the build completes (e.g., in `msbuild.doLast` or in a task that depends on `msbuild`), as artifacts are only collected after the build finishes.

### Legacy: Using `msbuild.projects`

For projects where parsing succeeds, you can still access project information via `msbuild.projects`:

```groovy
msbuild {
    doLast {
        def project = msbuild.projects['MyProject']
        def targetPath = project.properties.TargetPath
        // ...
    }
}
```

However, `msbuild.projects` may be empty when:
- Project parsing is skipped for old .NET Framework projects
- Project parsing fails for any reason

In these cases, use `msbuild.archive()` or `msbuild.artifacts` instead, which work by scanning the actual build output directories.

assemblyInfoPatcher {
  // mandatory if you want to patch your AssemblyInfo.cs/fs/vb

  // replaces the AssemblyVersion value in your AssemblyInfo file.
  // when explicitly set to blank, AssemblyVersion will not be updated and will keep the existing value in your AssemblyInfo file
  // TODO: not yet normalized, beware than .Net version must be X.Y.Z.B format, with Z/B optionals
  version = project.version + '.0.0'

  // replaces the AssemblyFileVersion value in your AssemblyInfo file.
  // defaults to above version, fewer restrictions on the format
  // when explicitly set to blank, AssemblyFileVersion will not be updated and will keep the existing value in your AssemblyInfo file
  fileVersion = version + '-Beta'

  // replaces the AssemblyInformationalVersion value in your AssemblyInfo file.
  // defaults to above version, fewer restrictions on the format
  // when explicitly set to blank, AssemblyInformationalVersion will not be updated and will keep the existing value in your AssemblyInfo file
  informationalVersion = version + '-Beta'

  // replaces the AssemblyDescription in the your AssemblyInfo file.
  // when set to blank (default), AssemblyDescription will not be updated and will keep the existing value in your AssemblyInfo file
  description = 'My Project Description'

  // default to msbuild main project (of solution)
  projects = [ 'MyProject1', 'MyProject2' ]
}
```

## Custom tasks

You can create custom `msbuild` and `assemblyInfoPatcher` tasks like so:

```groovy
import com.ullink.Msbuild
import com.ullink.AssemblyInfoVersionPatcher

task compileFoo(type: Msbuild) {
    projectFile = "Foo.vcxproj"
    // Other properties
}

task versionPatchFoo(type: AssemblyInfoVersionPatcher) {
    projects = ['Foo']
    // Other properties
}
```

# See also

[Gradle NuGet plugin](https://github.com/Ullink/gradle-nuget-plugin) - Allows to restore NuGet packages prior to building the projects with this plugin, and to pack&push nuget packages.

[Gradle NUnit plugin](https://github.com/Ullink/gradle-nunit-plugin) - Allows to execute NUnit tests from CI (used with this plugin to build the projects prior to UT execution)

[Gradle OpenCover plugin](https://github.com/Ullink/gradle-opencover-plugin) - Allows to execute the UTs through OpenCover for coverage reports.

You can see these 4 plugins in use on [ILRepack](https://github.com/gluck/il-repack) project ([build.gradle](https://github.com/gluck/il-repack/blob/master/build.gradle)).

# License

All these plugins are licensed under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.html) with no warranty (expressed or implied) for any purpose.
