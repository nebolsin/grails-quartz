/*
 * Copyright (c) 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import grails.plugins.quartz.GrailsJobClassConstants as Constants

import grails.plugins.quartz.listeners.ExceptionPrinterJobListener
import grails.plugins.quartz.listeners.SessionBinderJobListener
import grails.util.GrailsUtil
import org.codehaus.groovy.grails.commons.ConfigurationHolder
import org.springframework.beans.factory.config.MethodInvokingFactoryBean
import org.springframework.context.ApplicationContext
import org.springframework.scheduling.quartz.SchedulerFactoryBean
import grails.plugins.quartz.*
import org.quartz.*

/**
 * A plug-in that configures Quartz job support for Grails.
 *
 *
 * @author Graeme Rocher
 * @author Marcel Overdijk
 * @author Sergey Nebolsin
 */
class QuartzGrailsPlugin {

    def version = "1.0-SNAPSHOT"
    def grailsVersion = "1.2 > *"

    def author = "Sergey Nebolsin"
    def authorEmail = "nebolsin@gmail.com"
    def title = "This plugin adds Quartz job scheduling features to Grails application."
    def description = '''\
Quartz plugin allows your Grails application to schedule jobs to be
executed using a specified interval or cron expression. The underlying
system uses the Quartz Enterprise Job Scheduler configured via Spring,
but is made simpler by the coding by convention paradigm.
'''
    def documentation = "http://grails.org/plugin/quartz"

    def license = "APACHE"
    def issueManagement = [system: "GitHub Issues", url: "http://github.com/nebolsin/grails-quartz/issues"]
    def scm = [url: "http://github.com/nebolsin/grails-quartz"]

    def loadAfter = ['core', 'hibernate', 'datasources']
    def watchedResources = [
            "file:./grails-app/jobs/**/*Job.groovy",
            "file:./plugins/*/grails-app/jobs/**/*Job.groovy"
    ]

    def artefacts = [new JobArtefactHandler()]

    def doWithSpring = {

        def config = loadQuartzConfig()

        application.jobClasses.each {jobClass ->
            configureJobBeans.delegate = delegate
            configureJobBeans(jobClass)
        }

        if (manager?.hasGrailsPlugin("hibernate")) {
            // register SessionBinderJobListener to bind Hibernate Session to each Job's thread
            "${SessionBinderJobListener.NAME}"(SessionBinderJobListener) {bean ->
                bean.autowire = "byName"
            }
        }

        // register global ExceptionPrinterJobListener which will log exceptions occured
        // during job's execution
        "${ExceptionPrinterJobListener.NAME}"(ExceptionPrinterJobListener)

        quartzJobFactory(GrailsJobFactory)

        quartzScheduler(SchedulerFactoryBean) {
            quartzProperties = config._properties

            // delay scheduler startup to after-bootstrap stage
            autoStartup = false
            if (config.jdbcStore) {
                dataSource = ref('dataSource')
                transactionManager = ref('transactionManager')
            }
            waitForJobsToCompleteOnShutdown = config.waitForJobsToCompleteOnShutdown
            exposeSchedulerInRepository = config.exposeSchedulerInRepository
            jobFactory = quartzJobFactory
            if (manager?.hasGrailsPlugin("hibernate")) {
                jobListeners = [ref("${SessionBinderJobListener.NAME}")]
            }
            globalJobListeners = [ref("${ExceptionPrinterJobListener.NAME}")]
        }
    }

    def doWithDynamicMethods = {ctx ->
        def random = new Random()
        Scheduler quartzScheduler = ctx.getBean('quartzScheduler')
        application.jobClasses.each {GrailsJobClass tc ->
            def mc = tc.metaClass
            def jobName = tc.getFullName()
            def jobGroup = tc.getGroup()

            def generateTriggerName = {->
                long r = random.nextLong()
                if (r < 0) {
                    r = -r;
                }
                return "GRAILS_" + Long.toString(r, 30 + (int) (System.currentTimeMillis() % 7));

            }

            mc.'static'.schedule = { String cronExpression, Map params = null ->
                Trigger trigger = new CronTrigger(generateTriggerName(), Constants.DEFAULT_TRIGGERS_GROUP, jobName, jobGroup, cronExpression)
                if (tc.getVolatility()) trigger.setVolatility(true)
                if (params) trigger.jobDataMap.putAll(params)
                quartzScheduler.scheduleJob(trigger)
            }
            mc.'static'.schedule = {Long interval, Integer repeatCount = SimpleTrigger.REPEAT_INDEFINITELY, Map params = null ->
                Trigger trigger = new SimpleTrigger(generateTriggerName(), Constants.DEFAULT_TRIGGERS_GROUP, jobName, jobGroup, new Date(), null, repeatCount, interval)
                if (tc.getVolatility()) trigger.setVolatility(true)
                if (params) trigger.jobDataMap.putAll(params)
                quartzScheduler.scheduleJob(trigger)
            }
            mc.'static'.schedule = {Date scheduleDate ->
                Trigger trigger = new SimpleTrigger(generateTriggerName(), Constants.DEFAULT_TRIGGERS_GROUP, jobName, jobGroup, scheduleDate, null, 0, 0)
                if (tc.getVolatility()) trigger.setVolatility(true)
                quartzScheduler.scheduleJob(trigger)
            }
            mc.'static'.schedule = {Date scheduleDate, Map params ->
                Trigger trigger = new SimpleTrigger(generateTriggerName(), Constants.DEFAULT_TRIGGERS_GROUP, jobName, jobGroup, scheduleDate, null, 0, 0)
                if (tc.getVolatility()) trigger.setVolatility(true)
                if (params) trigger.jobDataMap.putAll(params)
                quartzScheduler.scheduleJob(trigger)
            }
            mc.'static'.schedule = {Trigger trigger ->
                trigger.jobName = jobName
                trigger.jobGroup = jobGroup
                quartzScheduler.scheduleJob(trigger)
            }
            mc.'static'.triggerNow = { Map params = null ->
                if (tc.getVolatility()) {
                    quartzScheduler.triggerJobWithVolatileTrigger(jobName, jobGroup, params ? new JobDataMap(params) : null)
                } else {
                    quartzScheduler.triggerJob(jobName, jobGroup, params ? new JobDataMap(params) : null)
                }
            }
            mc.'static'.removeJob = {
                quartzScheduler.deleteJob(jobName, jobGroup)
            }

            mc.'static'.reschedule = {Trigger trigger ->
                trigger.jobName = jobName
                trigger.jobGroup = jobGroup
                quartzScheduler.rescheduleJob(trigger.name, trigger.group, trigger)
            }

            mc.'static'.unschedule = {String triggerName, String triggerGroup = Constants.DEFAULT_TRIGGERS_GROUP ->
                quartzScheduler.unscheduleJob(triggerName, triggerGroup)
            }
        }
    }

    def doWithApplicationContext = {applicationContext ->
        application.jobClasses.each {jobClass ->
            scheduleJob.delegate = delegate
            scheduleJob(jobClass, applicationContext)
        }
    }

    def onChange = {event ->
        if (application.isArtefactOfType(JobArtefactHandler.TYPE, event.source)) {
            log.debug("Job ${event.source} changed. Reloading...")
            def context = event.ctx
            def scheduler = context?.getBean("quartzScheduler")
            // get quartz scheduler
            if (context && scheduler) {
                // if job already exists, delete it from scheduler
                def jobClass = application.getJobClass(event.source?.name)
                if (jobClass) {
                    scheduler.deleteJob(jobClass.fullName, jobClass.group)
                    log.debug("Job ${jobClass.fullName} deleted from the scheduler")
                }

                // add job artefact to application
                jobClass = application.addArtefact(JobArtefactHandler.TYPE, event.source)

                // configure and register job beans
                def fullName = jobClass.fullName
                def beans = beans {
                    configureJobBeans.delegate = delegate
                    configureJobBeans(jobClass)
                }

                context.registerBeanDefinition("${fullName}Class", beans.getBeanDefinition("${fullName}Class"))
                context.registerBeanDefinition("${fullName}", beans.getBeanDefinition("${fullName}"))
                context.registerBeanDefinition("${fullName}Detail", beans.getBeanDefinition("${fullName}Detail"))

                jobClass.triggers.each {name, trigger ->
                    event.ctx.registerBeanDefinition("${name}Trigger", beans.getBeanDefinition("${name}Trigger"))
                }

                scheduleJob(jobClass, event.ctx)
            } else {
                log.error("Application context or Quartz Scheduler not found. Can't reload Quartz plugin.")
            }
        }
    }

    def scheduleJob = {GrailsJobClass jobClass, ApplicationContext ctx ->
        def scheduler = ctx.getBean("quartzScheduler")
        if (scheduler) {
            def fullName = jobClass.fullName
            // add job to scheduler, and associate triggers with it
            scheduler.addJob(ctx.getBean("${fullName}Detail"), true)
            jobClass.triggers.each {key, trigger ->
                log.debug("Scheduling $fullName with trigger $key: ${trigger}")
                if (scheduler.getTrigger(trigger.triggerAttributes.name, trigger.triggerAttributes.group)) {
                    scheduler.rescheduleJob(trigger.triggerAttributes.name, trigger.triggerAttributes.group, ctx.getBean("${key}Trigger"))
                } else {
                    scheduler.scheduleJob(ctx.getBean("${key}Trigger"))
                }
            }
            log.debug("Job ${jobClass.fullName} scheduled")
        } else {
            log.error("Failed to register job triggers: scheduler not found")
        }
    }

    def configureJobBeans = {GrailsJobClass jobClass ->
        def fullName = jobClass.fullName

        "${fullName}Class"(MethodInvokingFactoryBean) {
            targetObject = ref("grailsApplication", true)
            targetMethod = "getArtefact"
            arguments = [JobArtefactHandler.TYPE, jobClass.fullName]
        }

        "${fullName}"(ref("${fullName}Class")) {bean ->
            bean.factoryMethod = "newInstance"
            bean.autowire = "byName"
            bean.scope = "prototype"
        }

        "${fullName}Detail"(JobDetailFactoryBean) {
            name = fullName
            group = jobClass.group
            concurrent = jobClass.concurrent
            volatility = jobClass.volatility
            durability = jobClass.durability
            requestsRecovery = jobClass.requestsRecovery

            if (manager?.hasGrailsPlugin("hibernate") && jobClass.sessionRequired) {
                jobListenerNames = ["${SessionBinderJobListener.NAME}"] as String[]
            }
        }

        // registering triggers
        jobClass.triggers.each {name, trigger ->
            "${name}Trigger"(trigger.clazz) {
                jobDetail = ref("${fullName}Detail")
                trigger.properties.findAll {it.key != 'clazz'}.each {
                    delegate["${it.key}"] = it.value
                }
            }
        }
    }

    private ConfigObject loadQuartzConfig() {
        def config = ConfigurationHolder.config
        def classLoader = new GroovyClassLoader(getClass().classLoader)

        // merging default Quartz config into main application config
        config.merge(new ConfigSlurper(GrailsUtil.environment).parse(classLoader.loadClass('DefaultQuartzConfig')))

        // merging user-defined Quartz config into main application config if provided
        try {
            config.merge(new ConfigSlurper(GrailsUtil.environment).parse(classLoader.loadClass('QuartzConfig')))
        } catch (Exception ignored) {
            // ignore, just use the defaults
        }

        def properties = new Properties()
        def resource = classLoader.getResourceAsStream("quartz.properties")
        if (resource != null) {
            properties.load(resource)
        }

        if (config.quartz.containsKey('props')) {
            properties << config.quartz.props.toProperties('org.quartz')
        }

        config.quartz._properties = properties

        return config.quartz
    }
}
