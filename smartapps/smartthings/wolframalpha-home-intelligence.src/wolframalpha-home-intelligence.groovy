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
 *  WolframAlpha Home Intelligence
 *
 *  Queries WolframAlpha for home energy and environment calculations on a daily schedule
 *  and delivers the results as push or SMS notifications.
 *
 *  Free App ID (up to 2,000 API calls/month) at: https://developer.wolframalpha.com
 */

definition(
    name: "WolframAlpha Home Intelligence",
    namespace: "smartthings",
    author: "SmartThings",
    description: "Ask WolframAlpha home energy and climate questions on a schedule and receive the answers as push or SMS notifications.",
    category: "Convenience",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    pausable: true
)

// Preset questions suited to home automation context
@Field static final Map PRESET_QUERIES = [
    "Energy cost of running a 100W bulb for 1 hour at \$0.12/kWh": "cost of 100 watt bulb for 1 hour at \$0.12 per kWh",
    "Energy cost of a refrigerator per month at \$0.12/kWh"      : "cost of 150 watt appliance for 30 days at \$0.12 per kWh",
    "Convert 68°F to Celsius"                                     : "convert 68 degrees Fahrenheit to Celsius",
    "Recommended indoor humidity range"                           : "recommended indoor relative humidity",
    "CO2 saved by switching to LED lighting"                      : "CO2 reduction replacing 60W incandescent with 10W LED"
]

preferences {
    page(name: "mainPage", install: true, uninstall: true)
}

def mainPage() {
    dynamicPage(name: "mainPage") {
        section("WolframAlpha API") {
            paragraph "Get a free App ID at developer.wolframalpha.com"
            input "appId", "text", title: "App ID", required: true
        }
        section("Query") {
            input "queryMode", "enum",
                title: "Query type",
                options: ["Preset question", "Custom question"],
                defaultValue: "Preset question",
                required: true,
                submitOnChange: true
            if (queryMode == "Preset question") {
                input "presetQuery", "enum",
                    title: "Select a question",
                    options: PRESET_QUERIES.keySet().toList(),
                    required: true
            } else {
                input "customQuery", "text",
                    title: "Enter your own question",
                    required: true
            }
        }
        section("Schedule") {
            input "queryTime", "time", title: "Time to run the query each day", required: true
        }
        section("Notifications") {
            input("recipients", "contact", title: "Send results to") {
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
    log.debug "WolframAlpha Home Intelligence installed"
    initialize()
}

def updated() {
    log.debug "WolframAlpha Home Intelligence updated"
    unschedule()
    initialize()
}

def initialize() {
    schedule(queryTime, "runQuery")
}

def runQuery() {
    def question = resolvedQuery()
    if (!question) {
        log.warn "WolframAlpha: no query configured"
        return
    }

    log.debug "WolframAlpha querying: ${question}"

    def params = [
        uri  : "https://api.wolframalpha.com",
        path : "/v2/query",
        query: [
            appid : appId,
            input : question,
            format: "plaintext",
            output: "JSON"
        ]
    ]

    try {
        httpGet(params) { resp ->
            if (resp.status == 200) {
                handleQueryResponse(question, resp.data)
            } else {
                log.error "WolframAlpha: unexpected status ${resp.status}"
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        log.error "WolframAlpha HTTP error ${e.statusCode}: ${e.message}"
    } catch (Exception e) {
        log.error "WolframAlpha: unexpected error: ${e.message}"
    }
}

private handleQueryResponse(String question, data) {
    def queryResult = data?.queryresult
    if (!queryResult || queryResult.success == false) {
        def didYouMean = queryResult?.didyoumean?.collect { it.val }?.join("; ")
        def msg = didYouMean ?
            "WolframAlpha couldn't answer \"${question}\". Did you mean: ${didYouMean}?" :
            "WolframAlpha couldn't answer \"${question}\"."
        log.warn msg
        sendResult(msg)
        return
    }

    // Collect plaintext from all Result/Primary pods
    def resultPods = queryResult.pods?.findAll { pod ->
        pod.id == "Result" || pod.primary == true || pod.title == "Result"
    }

    def answer = resultPods?.collectMany { pod ->
        pod.subpods?.collect { it.plaintext }?.findAll { it }
    }?.join("\n")

    if (!answer) {
        // Fall back to the first pod with plaintext content
        answer = queryResult.pods?.collectMany { pod ->
            pod.subpods?.collect { it.plaintext }?.findAll { it }
        }?.find { it }
    }

    def msg = answer ?
        "WolframAlpha: ${question}\n→ ${answer}" :
        "WolframAlpha returned no readable answer for \"${question}\"."

    log.debug msg
    sendResult(msg)
}

private String resolvedQuery() {
    if (queryMode == "Custom question") {
        return customQuery?.trim()
    }
    return PRESET_QUERIES[presetQuery]
}

private sendResult(String message) {
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
