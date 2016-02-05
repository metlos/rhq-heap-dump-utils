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

        return [
                objectId           : resourceObjectId,
                id                 : getDetail(details, "id")["value"],
                name               : getDetail(details, "name")["value"],
                resourceKey        : getDetail(details, "resourceKey")["value"],
                pluginConfiguration: getConfiguration(getDetail(details, "pluginConfiguration")["objectId"]),
                children           : childResources.collect {
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

    private Map<String, String> getDetail(List<String> html, String detail) {
        def value = html.findAll {it.startsWith(detail)}[0]
        def matcher = value =~
                /$detail \(\w\) : (<a href="..\/object\/(0x[0-9a-f]+)">)?(.*)(( \(\d+ bytes\)<\/a>)|<br>)$/
        if (!matcher.matches()) {
            println(value)
        }
        ["objectId": matcher.group(2), "value": matcher.group(3).replace("&quot;", "\"")]
    }

    private List<String> getObjectDetails(String objectId) {
        def result = client.get(path: "/object/$objectId", contentType: ContentType.TEXT)
        result.data.readLines()
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

