import org.apache.camel.Exchange
import org.apache.camel.Message
import org.apache.camel.Processor
import org.apache.camel.groovy.extend.CamelGroovyMethods
import org.apache.camel.model.ChoiceDefinition
import org.apache.camel.model.ProcessorDefinition
import org.grails.plugins.routing.RouteArtefactHandler
import org.apache.camel.model.*
import org.grails.plugins.routing.processor.PredicateProcessor
import org.springframework.beans.factory.config.MethodInvokingFactoryBean

import javax.activation.DataHandler

import javax.security.auth.Subject

class RoutingGrailsPlugin {
	def version          = '1.4.1.1-SNAPSHOT'
	def grailsVersion    = '2.0.0 > *'
	def artefacts        = [new RouteArtefactHandler()]
	def dependsOn        = [:]
	def loadAfter        = [ 'controllers', 'services' ]
	def author           = 'Matthias Hryniszak, Chris Navta, Arief Hidaya'
	def authorEmail      = 'padcom@gmail.com, chris@ix-n.com'
	def documentation    = 'http://grails.org/plugin/routing'
	def title            = 'Apache Camel Plugin'
	def description      = 'Provides message routing capabilities using Apache Camel'

	def license = "APACHE"
	def developers = [
		[name: "Matthias Hryniszak", email: "padcom@gmail.com"],
		[name: "Chris Navta", email: "chris@ix-n.com"],
		[name: "Arsen A. Gutsal", email: "gutsal.arsen@gmail.com"]
	]
	def issueManagement = [system: "GITHUB", url: "https://github.com/padcom/grails-routing/issues"]
	def scm = [url: "https://github.com/padcom/grails-routing"]

	def doWithSpring = {
		def config = application.config.grails.routing

        // if camelContextId is changed then you have to use the 'new' name to inject the camelContext into
        // grails code
		def camelContextId = config?.camelContextId ?: 'camelContext'
		def useMDCLogging = config?.useMDCLogging ?: false
		def streamCache = config?.streamCache ?: false
		def trace = config?.trace ?: false
        def useSpringSecurity =  config?.useSpringSecurity ?: false
        def authorizationPolicies = config?.authorizationPolicies ?: []
		def routeClasses = application.routeClasses

		jmsConnectionFactory(org.apache.activemq.ActiveMQConnectionFactory) {
			brokerURL = config?.brokerURL ?: 'vm://LocalBroker'
			userName  = config?.userName  ?: ''
            password  = config?.password  ?: ''
		}

		pooledConnectionFactory(org.apache.activemq.pool.PooledConnectionFactory) { bean ->
			bean.initMethod = 'start'
			bean.destroyMethod = 'stop'
			maxConnections = config?.maxConnections ?: 8
			connectionFactory = ref('jmsConnectionFactory')
		}

		jmsConfig(org.apache.camel.component.jms.JmsConfiguration) {
			connectionFactory = ref('pooledConnectionFactory')
			concurrentConsumers = config?.concurrentConsumers ?: 10
		}

		activemq(org.apache.activemq.camel.component.ActiveMQComponent) {
			configuration = ref('jmsConfig')
		}

		initializeRouteBuilderHelpers()

		routeClasses.each { routeClass ->
			def fullName = routeClass.fullName

			"${fullName}Class"(MethodInvokingFactoryBean) {
				targetObject = ref("grailsApplication", true)
				targetMethod = "getArtefact"
				arguments = [RouteArtefactHandler.ROUTE, fullName]
			}

			"${fullName}"(ref("${fullName}Class")) { bean ->
				bean.factoryMethod = "newInstance"
				bean.autowire = "byName"
			}
		}

	  if(useSpringSecurity) {
          
            xmlns camelSecure:'http://camel.apache.org/schema/spring-security'
            authorizationPolicies?.each {
                camelSecure.authorizationPolicy(id : it.id, access: it.access,
                        accessDecisionManager : it.accessDecisionManager ?: "accessDecisionManager",
                        authenticationManager: it.authenticationManager ?: "authenticationManager",
                        useThreadSecurityContext : it.useThreadSecurityContext ?: true,
                        alwaysReauthenticate : it.alwaysReauthenticate ?: false)
            }
	  }

		xmlns camel:'http://camel.apache.org/schema/spring'

		// we don't allow camel autostart regardless to autoStartup value
		// this may cause problems if autostarted camel start invoking routes which calls service/controller
		// methods, which use dynamically injected methods
		// because doWithDynamicMethods is called after doWithSpring
		camel.camelContext(id: camelContextId,
			               useMDCLogging: useMDCLogging,
			               autoStartup: false,
			               streamCache: streamCache,
			               trace: trace) {
            def threadPoolProfileConfig = config?.defaultThreadPoolProfile

			camel.threadPoolProfile(
				id: "defaultThreadPoolProfile",
				defaultProfile: "true",
				poolSize: threadPoolProfileConfig?.poolSize ?: "10",
				maxPoolSize: threadPoolProfileConfig?.maxPoolSize ?: "20",
				maxQueueSize: threadPoolProfileConfig?.maxQueueSize ?: "1000",
				rejectedPolicy: threadPoolProfileConfig?.rejectedPolicy ?: "CallerRuns")

			routeClasses.each { routeClass ->
				camel.routeBuilder(ref: "${routeClass.fullName}")
			}
			camel.template(id: 'producerTemplate')
		}
	}

	def doWithDynamicMethods = { ctx ->
		def template = ctx.getBean('producerTemplate')

		addDynamicMethods(application.controllerClasses, template)
		addDynamicMethods(application.serviceClasses, template)

		if (isQuartzPluginInstalled(application)) {
			addDynamicMethods(application.jobClasses, template)
		}

		// otherwise we autostart camelContext here
		def config = application.config.grails.routing
		def autoStartup = config?.autoStartup ?: true

		if (autoStartup) {
			def camelContextId = config?.camelContextId ?: 'camelContext'
			application.mainContext.getBean(camelContextId).startAllRoutes()
		}
	}

	def watchedResources = [
		"file:./grails-app/controllers/**/*Controller.groovy",
		"file:./grails-app/services/**/*Service.groovy"
	]

	def onChange = { event ->
		def artifactName = "${event.source.name}"

		if (artifactName.endsWith('Controller') || artifactName.endsWith('Service')) {
			def artifactType = (artifactName.endsWith('Controller')) ? 'controller' : 'service'
			def grailsClass = application."${artifactType}Classes".find { it.fullName == artifactName }
			addDynamicMethods([grailsClass], event.ctx.getBean('producerTemplate'))
		}
	}

	// ------------------------------------------------------------------------
	// Private methods
	// ------------------------------------------------------------------------

	private initializeRouteBuilderHelpers() {
        //
        // only filter predicate. but looks like it's been handled. https://camel.apache.org/groovy-dsl.html
		ProcessorDefinition.metaClass.filter = { filter ->
			if (filter instanceof Closure) {
				filter = new PredicateProcessor(filter)
			}
		}
		ChoiceDefinition.metaClass.when = { filter ->
			if (filter instanceof Closure) {
                filter = new PredicateProcessor(filter)
			}
		}
		ProcessorDefinition.metaClass.process = { filter ->
			if (filter instanceof Closure) {
				CamelGroovyMethods.process(delegate, filter)
			} else {
				delegate.process(filter)
			}
		}
	}

	private addDynamicMethods(artifacts, template) {
		artifacts?.each { artifact ->
            artifact.metaClass.sendMessageWithAuth = { endpoint, message, auth ->
                def headers = [:];
                headers.put(Exchange.AUTHENTICATION, new Subject(true, [auth] as Set, [] as Set, [] as Set))
                template.sendBodyAndHeaders(endpoint,message, headers)
            }
            artifact.metaClass.requestMessageWithAuth = { endpoint, message, auth ->
                def headers = [:];
                headers.put(Exchange.AUTHENTICATION, new Subject(true, [auth] as Set, [] as Set, [] as Set))
                template.requestBodyAndHeaders(endpoint,message, headers)
            }
			artifact.metaClass.sendMessage = { endpoint,message ->
				template.sendBody(endpoint,message)
			}
			artifact.metaClass.sendMessageAndHeaders = { endpoint, message, headers ->
				template.sendBodyAndHeaders(endpoint,message,headers)
			}
			artifact.metaClass.sendMessageAndHeadersAndAttachments = { endpoint, message, headers, Map<String, DataHandler> attachments ->
				template.send( endpoint, { Exchange exchange ->
					Message msg = exchange.in;
					msg.setBody(message)
					msg.setHeaders(headers)
					attachments.each {
						msg.addAttachment(it.key, it.value)
					}
				} as Processor)
			}
			artifact.metaClass.requestMessage = { endpoint,message ->
				template.requestBody(endpoint,message)
			}
			artifact.metaClass.requestMessageAndHeaders = { endpoint, message, headers ->
				template.requestBodyAndHeaders(endpoint, message, headers)
			}
		}
	}

	private isQuartzPluginInstalled(application) {
		// this is a nasty implementation... maybe there's something better?
		try {
			def tasks = application.jobClasses
			return true
		} catch (e) {
			return false
		}
	}
}
