grails.project.work.dir = 'target'

def camelVersion = '2.13.2'

grails.project.fork = [

        // configure settings for the test-app JVM, uses the daemon by default

        test: [maxMemory: 768, minMemory: 64, debug: false, maxPerm: 256, daemon: true],

// configure settings for the run-app JVM

        run: [maxMemory: 768, minMemory: 64, debug: false, maxPerm: 256, forkReserve: false],

// configure settings for the run-war JVM

        war: [maxMemory: 768, minMemory: 64, debug: false, maxPerm: 256, forkReserve: false],

// configure settings for the Console UI JVM

        console: [maxMemory: 768, minMemory: 64, debug: false, maxPerm: 256]

]

grails.project.dependency.resolver = "maven" // or ivy

grails.project.dependency.resolution = {

	inherits 'global'
	log 'warn'

	repositories {
		grailsCentral()
		mavenLocal()
		mavenCentral()
	}

	dependencies {
		compile("org.apache.camel:camel-core:${camelVersion}") {
			excludes 'slf4j-log4j12', 'slf4j-api', 'camel-test'
		}
		compile("org.apache.camel:camel-spring:${camelVersion}") {
			excludes 'spring-aop', 'spring-beans', 'spring-core', 'spring-expression', 
			'spring-asm', 'spring-tx', 'spring-context', 'spring-jdbc', 'spring-test', 'slf4j-log4j12', 'slf4j-api', 'camel-test'
		}
		compile("org.apache.camel:camel-groovy:${camelVersion}") {
			excludes 'spring-context', 'spring-aop', 'spring-tx', 'groovy-all', 'slf4j-log4j12', 'spring-test',
			'spring-jdbc', 'camel-test'
		}
		compile("org.apache.camel:camel-stream:${camelVersion}")
		{
			excludes 'camel-core', 'camel-test', 'slf4j-log4j12'
		}

		test("org.apache.camel:camel-test:${camelVersion}") { excludes "junit", 'camel-core', 'slf4j-log4j12' }
	}

	plugins {
		build ':release:3.0.1', ':rest-client-builder:2.0.1', {
                  //			export = false
		}
	}
}
