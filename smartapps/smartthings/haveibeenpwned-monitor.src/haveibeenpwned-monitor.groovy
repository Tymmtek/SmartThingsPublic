/**
 *  Copyright 2024 SmartThings
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Have I Been Pwned Monitor
 *
 *  Periodically checks email addresses against the HaveIBeenPwned.com API and sends
 *  push/SMS notifications when a new data breach is detected.
 *
 *  API key required — obtain one at: https://haveibeenpwned.com/API/Key
 */

definition(
    name: "Have I Been Pwned Monitor",
    namespace: "smartthings",
    author: "SmartThings",
    description: "Monitor email addresses for data breaches using HaveIBeenPwned.com and receive push notifications when a new breach is detected.",
    category: "Safety & Security",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/SafetyAndSecurity/App-SevereWeather.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/SafetyAndSecurity/App-SevereWeather@2x.png",
    pausable: true
)

preferences {
    page(name: "mainPage", install: true, uninstall: true)
}

def mainPage() {
    dynamicPage(name: "mainPage") {
        section("HaveIBeenPwned API") {
            paragraph "Get your API key at haveibeenpwned.com/API/Key"
            input "apiKey", "text", title: "API Key", required: true
        }
        section("Email Addresses to Monitor") {
            input "emailAddresses", "text", title: "Email address(es) — separate multiple with commas", required: true
        }
        section("Check Frequency") {
            input "checkInterval", "enum", title: "How often to check", options: ["Daily", "Weekly"], defaultValue: "Daily", required: true
        }
        section("Notifications") {
            input("recipients", "contact", title: "Send notifications to") {
                input "sendPushNotification", "bool", title: "Send push notification", defaultValue: true, required: false
                input "phone", "phone", title: "Send SMS to", required: false
            }
        }
        section([mobileOnly: true]) {
            label title: "Assign a name", required: false
            mode title: "Set for specific mode(s)"
        }
    }
}

def installed() {
    log.debug "Have I Been Pwned Monitor installed"
    initialize()
}

def updated() {
    log.debug "Have I Been Pwned Monitor updated"
    unschedule()
    initialize()
}

def initialize() {
    if (!state.knownBreaches) {
        state.knownBreaches = [:]
    }
    scheduleCheck()
    // Run an initial check shortly after install
    runIn(10, "checkForBreaches")
}

def scheduleCheck() {
    def sec = Math.round(Math.floor(Math.random() * 60))
    def min = Math.round(Math.floor(Math.random() * 60))

    if (checkInterval == "Weekly") {
        schedule("${sec} ${min} 9 ? * SUN", "checkForBreaches")
    } else {
        def hour = 6 + Math.round(Math.floor(Math.random() * 6))
        schedule("${sec} ${min} ${hour} * * ?", "checkForBreaches")
    }
}

def checkForBreaches() {
    log.debug "Checking for data breaches..."
    def emails = emailAddresses.split(",").collect { it.trim() }.findAll { it }

    emails.each { email ->
        checkEmailForBreaches(email)
        pause(1600) // Respect HIBP rate limit: max 1 req/1.5 s per API key
    }
}

def checkEmailForBreaches(String email) {
    def encoded = email.encodeAsURL()
    def params = [
        uri    : "https://haveibeenpwned.com",
        path   : "/api/v3/breachedaccount/${encoded}",
        headers: [
            "hibp-api-key": apiKey,
            "User-Agent"  : "SmartThings-HIBP-Monitor/1.0"
        ],
        query  : [truncateResponse: "false"]
    ]

    try {
        httpGet(params) { resp ->
            if (resp.status == 200) {
                handleBreachResponse(email, resp.data)
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        switch (e.statusCode) {
            case 404:
                // 404 = good news: no breaches found for this address
                state.knownBreaches[email.toLowerCase()] = []
                log.debug "No breaches found for ${email}"
                break
            case 401:
                log.error "HIBP: invalid or missing API key"
                break
            case 429:
                log.warn "HIBP: rate limit exceeded — will retry on next scheduled check"
                break
            default:
                log.error "HIBP HTTP error ${e.statusCode} for ${email}"
        }
    } catch (Exception e) {
        log.error "HIBP: unexpected error for ${email}: ${e.message}"
    }
}

private handleBreachResponse(String email, List breaches) {
    def emailKey = email.toLowerCase()
    def currentNames = breaches.collect { it.Name }
    def previousNames = state.knownBreaches[emailKey] ?: []
    def newNames = currentNames - previousNames

    if (newNames) {
        newNames.each { name ->
            def breach = breaches.find { it.Name == name }
            def title = breach?.Title ?: name
            def dataClasses = breach?.DataClasses?.join(", ") ?: "unknown data"
            def msg = "Security Alert: ${email} appeared in the '${title}' breach. Exposed: ${dataClasses}. Visit haveibeenpwned.com for details."
            log.warn msg
            sendAlert(msg)
        }
        state.knownBreaches[emailKey] = currentNames
    } else {
        log.debug "No new breaches for ${email} (${currentNames.size()} known)"
    }
}

private sendAlert(String message) {
    if (location.contactBookEnabled && recipients) {
        sendNotificationToContacts(message, recipients)
    } else {
        if (sendPushNotification) {
            sendPush message
        }
        if (phone) {
            sendSms phone, message
        }
    }
}
