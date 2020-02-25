## Request Chain

Request Chain is a command line utility that allows for executing
a chain of synchronous, ordered HTTP requests while passing data from
the execution context of one request to subsequent requests. This allows
for users to easily test web API paths without writing code or having to
execute API calls manually, one at a time, in an HTTP client application.
At present, only JSON data is supported in both requests and responses;
binary, form data, xml, etc is not (yet) supported.

Execution of the utility requires the creation of a configuration JSON file,
which should contain a JSON object that specifies how the utility should execute.
There is only one required config value, 'requestsUri'. The value of this configuration
entry specifies a JSON file that contains the request objects that should be executed.
In future versions it may be possible to supply an HTTP endpoint instead that resolves to a
configuration JSON object of the same format.

There are three ways to run the utility. Again, no matter how you run it, you must supply it with
a valid path to a configuration file. One way to run the utility is to open this project in a Kotlin-capable
IDE (only tested with IntelliJ) and use the internal tools to run the main function. Another way to run
the utility is to run the included req_chain.kts standalone script from the command line. Running the
 utility this way requires the installation of [kscript](https://github.com/holgerbrandl/kscript):

    kscript req_chain.kts --configFilePath=~/my/config/file.json
    
Finally, you can run it with the included standalone binary:

    ./req_chain --configFilePath=~/my/config/file.json

This does not require the installation of kscript unless you plan to modify any functionality, 
in which case you would need to repackage the binary after making modifications to the req_chain.kts
kscript using the following command:

    kscript --package req_chain.kts

An example config file is also included (reqconfig.json) as a reference. 
The file specified can have up to six configuration values 
specified (the purposes of most of these are described further in this document,
the rest should be self explanatory):

    {
	    "requestsUri": "~/my/requests.json",
	    "mockResponsesUri": "~/my/mockResponses.json",
	    "useMocks": true,
	    "useEnv": true,
	    "logRequests": true,
	    "logResponses": true,
	    "templateString": "my template string: ${id}, ${token}"
    }

The json file specified by requestsUri should contain an array of json objects, each 
of which represents a single HTTP request. The schema of these objects (not a formal JSON schema, yet)
allows for the declarative execution of the HTTP requests they describe,
which includes mapping response values to subsequent requests and performing
assertions on response data to test assumptions and functionality.
There is an example of such a requests file for reference under src/main/resources/example.

Each request will be executed starting from the top of the requests file. 
Each request object contains an optional "chainMappings" JSON array that 
allows values from the response of that request to be mapped into headers, 
parameters, or body objects of the following request, allowing
for requests to be chained together. For example, the first request
might return an API token in the response. This API token could then be
mapped into the Authorization header of the next request, allowing for
the next request to make authorized API calls.

The values of the JSON strings in which you want to inject values
from previous responses are "templatized" according to certain values 
specified in the chainMappings object. For example, you might have 
a mapping like this:

    {
        "useResponseKey": "access_token",
        "mapTo": "headers.Authorization",
        "preserveValueAs": "token"
    }

This mapping effectively says "In the response JSON to this request
there should be a value (at the root) with a key called 'access_token'. 
Take that value and map it to the value of the next request's 
headers object under the 'Authorization' key. Additionally, preserve
this value for use in future mappings." In the next request object 
you should then see a "headers" object with an 'Authorization' key,
something like this:

    "headers": {
      "Content-Type": "application/json",
      "Authorization": "Bearer <token>"
    }
    
You can see here the 'Authorization' value is templated with
\<token\>, which means the value obtained from 'access_token'
in the previous response will be inserted there, as specified by the
'preserveValueAs' key-value pair in the mapping. We use the value of
'preserveValueAs' here instead of the value of 'useResponseKey' so that we
can change how the mapped data is named rather than being forced
to use the names from the response objects (and hoping there are
no collisions), but if 'preserveValueAs' is left out, it
will fall back to the value specified by 'useResponseKey', which
of course means the headers object would need to look like this
instead:

    "headers": {
      "Content-Type": "application/json",
      "Authorization": "Bearer <access_token>"
    }
    
A key aspect of mapping response values from one request to the next is the
ability to inject not just header values, but parameters and other parts of a
request object. For instance, path parameters can be injected by mapping values 
to the "endpoint" object of a request object. A particular request 
might return information about a resource which belongs to a collection with a GET 
request to a path like /api/my/resource/3. The response to this GET request might look 
like this:

    {
        "id": 3,
        "name": "my resource",
        "collections": [
            {
                "id": 99,
                "name": "my collection"
            }
            ...
        ]
    }

We might then want to make the next request in the chain retrieve 
information about the first collection the resource belongs to. To do this, we could set
up a mapping in the GET request for /api/my/resource/3 that looks like this:

    {
        "useResponseKey": "collections[0].id",
        "mapTo": "endpoint.path",
        "preserveValueAs": "collectionId"
    }
    
Then the next request object's endpoint structure would look something like this:

    "endpoint": {
        "scheme": "http",
        "host": "localhost",
        "port": 8080,
        "path": "/api/my/collection/<collectionId>",
        "type": "GET"
    }
    
This combination of request -> response -> mapping -> endpoint would cause the GET 
request for the collection to go to the path

    /api/my/collection/99

Here the collection ID 99 returned in the response to the request to /api/my/resource/3
has been injected into the next request's path. You can inject any value into any of the
request object structures in a similar fashion.

To utilize a value from any previous response in an arbitrary request,
'preserveValueAs' saves the value specified in a map that can be
accessed at any subsequent point in the chain using the name given (in the 
example above: 'token'). To provide such a preserved value to
the next request in the chain you would use a mapping like this:

    {
        "usePreservedValue": "token",
        "mapTo": "headers.Authorization"
    }
    
As you might expect, the insertion point in the next 
request will be specified by the value of the 'usePreservedValue'
object which is, once again, 'token'. You should not attempt to specify both
'usePreservedValue' and 'preserveValueAs' in the same mapping, as
the use of 'usePreservedValue' assumes that 'preserveValueAs' was
already used to save that value earlier in the chain.

It's important to keep in mind the life cycle of the requests in the
chain. When setting up mappings, you are providing values for the
NEXT request in the chain, NOT the current request. It is not
possible to insert values into the current request from its own
object in the requests file, only into the next request, with the exception
of the following: In order to avoid secrets from having to appear in a requests file
(so that, for example, a request for an API access token can succeed) and thus potentially
end up in a public repository, you can optionally provide the required data 
via environment variables. They can be called anything but they MUST start with "reqchain_". 
This is to prevent irrelevant and potentially sensitive environment variables from
leaking out. Attempting to reference environment variables that do not begin with
"reqchain_" will be ignored. Here's an example of some such environment variables:

    reqchain_client_id="PLiAGZ46qyc72yPNXoJ3t7b2f01LRvPL"
    reqchain_client_secret="ctYZvUsCd4sRklQzez4ieGcFUwx5k3XCgjrZmkdAbPvdeu4NQwpIlgEZUBs3bcb4"
    
You can then reference these environment variables in your requests file with the following
syntax:

    "body": {
          "client_id": "<env.reqchain_client_id>",
          "client_secret": "<env.reqchain_client_secret>",
          ...
    }
    
This breaks with the normal sequence of events described above, since these values do not
come from the response of an earlier request, but rather from the environment, so they 
can be referenced anywhere (for example, in the very first request in the requests file). 
When the utility executes it will attempt to retrieve any values of any environment variables 
referenced this way and insert them in the specified location. Currently, due to the nature of 
how the templating works, only strings are supported, but a mechanism to convert strings to non-string
types may be supported eventually. Additionally, you must explicitly enable this functionality
by setting the value of "useEnv" to _true_ in your config json file. Without this, references
to environment variables will be ignored.

In addition to mappings, you can make assertions to check that the 
data coming back from your requests is what you expect it to be. Assertions
are placed in a sibling JSON array to the chainMappings JSON array called
'responseAssertions', and look like this:

    {
        "checkResponseKey": "name",
        "expectedValue": "bob smith"
    }

When added to a request object, this assertion will check that the
response to the current request contains a root value called 'name'
with the value 'bob smith'. If it does not, the chain will (by default)
terminate with an error describing the assertion failure.

Note that the 'mapTo' object of the chainMappings and the
'checkResponseKey' of the assertions support simple pathing to address 
nested JSON values. The pathing uses dot notation to separate hierarchy 
levels, for example:

    {
        "checkResponseKey": "result.person.name",
        "expectedValue": "bob smith"
    }
    
Which assumes a JSON object in the response with a structure 
something like this:

    {
        "result": {
            "person": {
                "name": "bob smith"
            }
        }
    }
    
JSON arrays can be indexed as well:

    {
        "checkResponseKey": "result[0].person.name",
        "expectedValue": "bob smith"
    }

to address a structure like this:

    {
        "result": [
            "person": {
                "name": "bob smith"
            }
            ...
        ]
    }
    
If the response has an array at the root, it can be addressed with
a index notation at the start:

    {
        "checkResponseKey": "[0].person.name",
        "expectedValue": "bob smith"
    }
    
with this structure:

    [
        "person": {
            "name": "bob smith"
        }
        ...
    ]
    
Unsurprisingly, attempting to write a path to objects or arrays instead of primitive values
(strings, numbers or booleans) is not supported. It's very important to check your paths
for correctness, otherwise the utility may fail with inscrutable error messages.

### Other features

In addition to testing API functionality directly, the response data that is specified to be 
preserved throughout the execution of the chain (by using the 'preserveValueAs' mapping directive) 
can be returned after execution. The use of this directive creates and saves a key-value pair
in a map by (bear with me here) using the string value of the 'preserveValueAs' object as the _key_ in 
the pair and the value in the response specified by the path denoted by the value of the 'useResponseKey'
object as the _value_ in the pair. So for example if you had this mapping in a request:

    {
        "useResponseKey": "result.someValue",
        "mapTo": "params.myvalue",
        "preserveValueAs": "myValue"
    }
    
And the response to the request containing this mapping looked like this:

    {
        "result": {
            "someValue": 123
        }
    }
    
Then the key-value pair saved in the "preserved data" map would be:

    myValue -> 123
    
It's worth noting that if two mappings specify the same 'preserveValueAs' value, the later value
will overwrite the map entry for the earlier one, which may be useful for scrubbing access tokens
or other secrets from the output. The return of this map (as a JSON string) is the default end 
result of the execution of a request chain, and the data will be sent to stdout.

Responses can be mocked by specifying a JSON file in the configuration object detailed above
using the 'mockResponsesUri' value. This file should contain a JSON array with the raw JSON response
data corresponding to each respective request object in the requests file, ie each entry in
this array should correspond to the request object at the same place in that file. This
can be useful when initially writing the request objects if hitting live endpoints is
not feasible or convenient. This file will only be used if 'useMocks' is set to true
in the configuration object. There is an example of a mock resources json file under 
src/main/resources/example.

One final feature is the ability to send a template string to the
utility via the 'templateString' configuration value. The string can contain expression language
(ie "${key}") keys from the "preserved data" map mentioned above, which will be replaced by their 
values after execution. To continue with the example above, you could supply the following string 
to the utility:

    this is my special value: ${myValue}
    
Which, when combined with the mapping specified in the earlier example, would result
in the following being returned after execution:

    this is my special value: 123
    
If such a string is supplied, the "preserved value map" will not be returned after execution, 
replaced instead by the interpolated string.