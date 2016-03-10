/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


import groovy.json.JsonOutput
import groovyx.net.http.ContentType
import groovyx.net.http.RESTClient

/**
 * Assumes jhat with an RHQ agent heap dump running on localhost:7000 (the default).
 *
 * Given a resource ID (as its argument on the commandline) this will output the resource hierarchy of
 * that resource in JSON format extracted from the heap dump.
 *
 * This is a mavenized project, so to run the above (assuming jhat is running), do:
 * <code>
 *     mvn package exec:java -Dexec.mainClass=RHQHeapDumpBrowser -Dexec.arguments="42"
 * </code>
 *
 * That will output the resource hierarchy of resource with ID 42.
 */
class RHQHeapDumpBrowser {
    def client = new RESTClient("http://localhost:7000", ContentType.HTML)

    public String findResourceObjectId(int resourceId) {
        def result = client.get(path: "/oql/",
                query: ["query": "select objectid(r) from org.rhq.core.domain.resource.Resource r where r.id == $resourceId"])
        result.data.childNodes().find {it.name == "BODY"}.childNodes().drop(2).find {it.name == "TABLE"}.text().trim()
    }

    public getResourceDetails(int resourceId) {
        getResourceObjectDetails(findResourceObjectId(resourceId))
    }

    private getResourceObjectDetails(String resourceObjectId) {
        def details = getObjectDetails(resourceObjectId)

        def childResourcesId = getDetail(details, "childResources")["objectId"]
        def childResArray = getHierarchically(childResourcesId, "al", "array")
        def childResources = getArrayContents(childResArray)
//        def childResources = []
        def resourceTypeId = getDetail(details, "resourceType")["objectId"]
        def resourceTypeDetails = getObjectDetails(resourceTypeId)
        def resourceContainerId = getReferentObjectIds(details, "org.rhq.core.pc.inventory.ResourceContainer",
                "resource")[0]
        def resourceContainerDetails = getObjectDetails(resourceContainerId)

        return [
                objectId             : resourceObjectId,
                id                   : getDetail(details, "id")["value"],
                name                 : getDetail(details, "name")["value"],
                resourceKey          : getDetail(details, "resourceKey")["value"],
                availability         : getEnumName(getDetail(resourceContainerDetails, "currentAvailType")["objectId"]),
                pluginConfiguration  : getConfiguration(getDetail(details, "pluginConfiguration")["objectId"]),
                resourceConfiguration: getConfiguration(getDetail(details, "resourceConfiguration")["objectId"]),
                enabledMetrics       : getMetrics(getDetail(resourceContainerDetails, "measurementSchedule")
                        ["objectId"]),
                resourceType         : [
                        objectId                       : resourceTypeId,
                        id                             : getDetail(resourceTypeDetails, "id")["value"],
                        name                           : getDetail(resourceTypeDetails, "name")["value"],
                        plugin                         : getDetail(resourceTypeDetails, "plugin")["value"],
                        pluginConfigurationDefinition  : getConfigurationDefinition(getDetail(resourceTypeDetails,
                                "pluginConfigurationDefinition")["objectId"]),
                        resourceConfigurationDefinition: getConfigurationDefinition(getDetail(resourceTypeDetails,
                                "resourceTypeConfigurationDefinition")["objectId"])
                ],
                children             : childResources.collect {
                    getResourceObjectDetails(it.objectId)
                }.sort {a, b -> a.resourceKey.compareTo(b.resourceKey)}
        ]
    }

    private Map<String, String> getConfiguration(String configObjectId) {
        def table = getHierarchically(configObjectId, "properties", "table")
        def tableContents = getArrayContents(table)
        tableContents.collectEntries {
            def value = getHierarchically(it.objectId, "value")
            [(getDetail(value, "name")["value"]): getDetail(value, "stringValue")["value"]]
        }.sort {a, b -> a.key.compareTo(b.key)}
    }

    private Map<String, Object> getConfigurationDefinition(String configDefObjectId) {
        def table = getHierarchically(configDefObjectId, "propertyDefinitions", "table")
        def tableContents = getArrayContents(table)
        tableContents.collectEntries {
            def value = getHierarchically(it.objectId, "value")
            [(getDetail(value, "name")["value"]): [
                    defaultValue   : getDetail(value, "defaultValue")["value"],
                    enumeratedValue: getDetail(value, "enumeratedValue")["value"],
                    type           : getEnumName(getDetail(value, "type")["objectId"]),
                    unit           : getEnumName(getDetail(value, "unit")["objectId"])
            ]]
        }.sort {a, b -> a.key.compareTo(b.key)}
    }

    private String getEnumName(String availTypeObjectId) {
        getDetail(getObjectDetails(availTypeObjectId), "name")["value"]
    }

    private Map<String, Object> getMetrics(String measurementSchedulesObjectId) {
        def _set = getHierarchically(measurementSchedulesObjectId, "_set")
        def _setContents = getArrayContents(_set)
        _setContents.findAll {!it["value"].startsWith("java.lang.Object")}
                .collect {getObjectDetails(it["objectId"])}.
                findAll {
                    getDetail(it, "enabled")["value"] == "true"
                }.collectEntries {
                    [(getDetail(it, "name")["value"]): [
                            interval  : getDetail(it, "interval")["value"],
                            scheduleId: getDetail(it, "scheduleId")["value"],
                    ]]
                }
    }

    private Map<String, String> getDetail(List<String> html, String detail) {
        def value = html.findAll {it.startsWith(detail)}[0]
        def matcher = value =~
                /$detail \(\w\) : (<a href="..\/object\/(0x[0-9a-f]+)">)?(.*)(( \(\d+ bytes\)<\/a>)|<br>)$/
        if (!matcher.matches()) {
            [:]
        } else {
            ["objectId": matcher.group(2), "value": matcher.group(3).replace("&quot;", "\"")]
        }
    }

    private List<String> getObjectDetails(String objectId) {
        def result = client.get(path: "/object/$objectId", contentType: ContentType.TEXT)
        result.data.readLines()
    }

    private List<String> getReferentObjectIds(List<String> html, String referentType, String referringField) {
        def ret = []
        def skipNext = false
        html.eachWithIndex {line, i ->
            if (skipNext) {
                skipNext = false
            } else {
                def matcher = line =~
                        /<a href="..\/object\/(0x[0-9a-f]+)">$referentType@0x[a-f0-9]+ \(\d+ bytes\)<\/a>$/
                if (matcher.matches()) {
                    //check if the second line contains the correct referring field
                    def fieldMatcher = html[i + 1] =~ / : field $referringField<br>$/
                    if (fieldMatcher.matches()) {
                        ret += matcher.group(1)
                        skipNext = true
                    }
                }
            }
        }
        ret
    }

    private List<Map<String, String>> getArrayContents(List<String> details) {
        def nonNulls = details.findAll {
            it.indexOf("null") == -1 && it ==~ /^\d+ : .*>$/
        }

        nonNulls.collect {
            def matcher = it =~ /\d+ : (<a href="..\/object\/(0x[0-9a-f]+)">)?(.*) \(\d+ bytes\)(<\/a>)?$/
            matcher.matches()
            ["objectId": matcher.group(2), "value": matcher.group(3)]
        }
    }

    private List<String> getHierarchically(String objectId, String... properties) {
        def details = getObjectDetails(objectId)
        properties.each {
            def propId = getDetail(details, it)["objectId"]
            details = getObjectDetails(propId)
        }
        return details
    }

    static void main(String[] args) {
        def b = new RHQHeapDumpBrowser()
        def json = JsonOutput.prettyPrint(JsonOutput.toJson(b.getResourceDetails(Integer.valueOf(args[0]))))
        println(json)
    }
}

