package com.ullink
import groovy.xml.MarkupBuilder
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.internal.os.OperatingSystem
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification
import spock.lang.IgnoreIf

class MsbuildPluginSpec extends Specification {
    def msbuildPluginAddsMsbuildTaskToProject() {
        given:
        Project project = ProjectBuilder.builder().build()

        when:
        project.apply plugin: MsbuildPlugin

        then:
        project.tasks.msbuild instanceof Msbuild
    }

    @IgnoreIf({ !isMsBuildAvailable() })
    def testExecution() {
        given:
        def writer = new StringWriter()
        def xml = new MarkupBuilder(writer)
        xml.Project(ToolsVersion:"4.0", DefaultTargets:"Test", xmlns:"http://schemas.microsoft.com/developer/msbuild/2003") {
          Target(Name:'Test')
        }
        File file = File.createTempFile("temp",".scrap")
        file.with {
            deleteOnExit()
            write writer.toString()
        }

        when:
        Project p = ProjectBuilder.builder().build()
        p.apply plugin: MsbuildPlugin
        p.msbuild {
            projectFile = file
        }
        // Execute the task properly to trigger @TaskAction execute() method (Gradle 8/9 compatible)
        p.tasks.msbuild.actions.each { action ->
            action.execute(p.tasks.msbuild)
        }

        then:
        noExceptionThrown()
    }

    @IgnoreIf({ !isMsBuildAvailable() })
    def execution_nonExistentProjectFile_throwsGradleException() {
        given:
        Project p = ProjectBuilder.builder().build()

        when:
        p.apply plugin: MsbuildPlugin
        p.msbuild {
            projectFile = OperatingSystem.current().isWindows() ? 'C:\\con' : '/con' // we can never create a file called `con` in root
        }

        and:
        // Execute the task properly to trigger @TaskAction execute() method (Gradle 8/9 compatible)
        p.tasks.msbuild.actions.each { action ->
            action.execute(p.tasks.msbuild)
        }

        then:
        thrown(GradleException)
    }

    private static boolean isMsBuildAvailable() {
        // Skip tests if MSBuild is not available (e.g., on Linux CI without MSBuild installed)
        try {
            def resolver = new MsbuildResolver()
            def project = ProjectBuilder.builder().build()
            project.apply plugin: MsbuildPlugin
            resolver.setupExecutable(project.tasks.msbuild)
            return project.tasks.msbuild.msbuildDir != null
        } catch (Exception e) {
            return false
        }
    }
}
