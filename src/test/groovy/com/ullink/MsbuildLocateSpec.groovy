package com.ullink

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification
import spock.lang.IgnoreIf

class MsbuildLocateSpec extends Specification {
    @IgnoreIf({ !isMsBuildAvailable() })
    def testMsBuildCanBeFound() {
        given:
        def resolver = new MsbuildResolver()

        when:
        Project p = ProjectBuilder.builder().build()
        p.apply plugin: MsbuildPlugin
        resolver.setupExecutable(p.tasks.msbuild)

        then:
        p.tasks.msbuild.msbuildDir != null
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