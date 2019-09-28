# Harness Design Principles

The Harness design philosophy and target audience is:
 
 1. The Engineer who wants to run some collections of Machine Learning Engines but does not want to implement them or even modify them.
 2. The Data Scientist that wants to focus on creating an algorithm but wants to inherit input collection, query serving, security, and extensibility with best in class scalability.

Therefor design trade-offs have been made to favor flexibility and the ability to create turnkey Engines over making Engine development easier. That said, all Engine development must be easy to do, understand, and debug. We expect Template developers to be moderately skilled in Scala development. We expect users of Engines to be skilled in using REST APIs in some form, and we supply client Python and Java SDKs to aid in this. Unless the client application needs to connect over the internet (which requires SSL and Authentication) the client can use simple REST type communication so most any language will suffice. Once TLS/SSL and Authentication are used, the client must obey TLS and OAuth2 rules that the SDKs make easier.

Harness aims to maintain data level compatibility with Apache PredictionIO in terms of input and queries where the Engines are have identical functionality.

Harness architecture is rather fundamentally different that PredictionIO in order to support:

 - **Kappa and Lambda training**: online streaming learners as well as periodic background / batch training. PredictionIO only supports Lambda.
 - **Robust Data Validation**: input for Engines is defined in format and meaning by the Engine in both PredictionIO and Harness. But Harness expects the Engine to validate every input and report errors. 
 - **Input Stream**: since data is validated by the Engine it may also affect algorithm state so while PredictionIO treats the input as an immutable un-ending stream, Harness Engines may not. They are free to change the model in real-time, which is necessary to support online learning and often desirable for lambda learning. Therefor Harness exports are for backup only, they cannot be replayed into some other Engine. For this reason the input stream of events can be "Mirrored" to a filesystem for later replay. This pattern allows us to have both online streaming learning and lambda learning. Online learning modifies the model in real-time as input is gathered and therefore may discard input. If Mirroring is on, the input is stored for rebuilding the model, if it is off, no input for this type of learning is accumulated.  
 - **API**: The API of record for **all** of Harness is REST. Even the CLI is implemented through the REST API. This allows Harness to be administered remotely.
 - **SDKs**: Harness has a Python and a Java SDK (Scala may use the Java SDK as-is) The SDKs support input and queries only. Other admin CLI invokes admin REST APIs implemented only in the Python SDK. 3rd Party clients are encouraged for other languages. Please feel free to contribute them
 - **CLI**: The shell CLI is implemented as Python scripts that invoke the REST Admin API using the Python SDK.
 - **Security**: HTTPS TLS/SSL is supported for the REST interface. Server to Server OAuth2 based token authentication is also supported. This allows a remote CLI or web interface to access all functions of Harness securely over the internet in fact the Harness CLI can operate remotely.
 - **Multi-tenancy**: fundamental integral multi-tenancy of all the REST API is supported through the use of resource-ids. Input can be sent to any number of engines by directing events to the correct resource (see the REST API for details). This includes input, queries, and admin functions.  Here an "engine" can be seen as an instance of a Template with a particular algorithm, dataset, and parameters.

# Engines for Harness

An Engine is the API contract. This contract is embodied in the `core/engine` package as abstract classes that must be extended in each Engine type. There may be any number of Engine Instances with their own input data, resource-id, and parameters that use the same Engine code and algoirthm. This is the definition of multi-tenancy. These abstract classes define methods that must be overridden and not inherited as well as ones that must be overridden AND inherited. In other words some base level functionality is common to all Engines and is implemented in the  partially abstract classes (for instance Mirroring, where no Engine needs to implement this).

 - **Engine**: The Engine class is the "controller" in the MVC use of the term. It takes all input, parses and validates it, then understands where to send it and how to report errors. It also fields queries processing them in much the same way. It creates Datasets and Algorithms and understand when to trigger functionality for them. The basic API is defined but any additional workflow or object needed may be defined ad-hoc, where Harness does not provide sufficient support in some way. For instance a compute engine or storage method can be defined ad-hoc if Harness does not provide default sufficient default support.
 - **Algorithm**: The Algorithm understands where data is and implements the Machine Learning part of the system. It converts Datasets into Models and implements the Queries. The Engine controls initialization of the Algorithm with parameters and triggers training and queries. For kappa style learning the Engine triggers training on every input so the Algorithm may spin up Akka based Actors to perform continuous training. For Lambda the training may be periodic and performed less often and may use a compute engine like Spark or TensorFlow to perform this periodic training. There is nothing in the Harness that enforces the methodology or compute platform used but several are given default support to make their use easier.
 - **Dataset**: A Dataset may be comprised of event streams and mutable data structures in any combination. The state of these is always stored outside of Harness in a separate scalable sharable store. The Engine usually implements a `dal` or data access layer, which may have any number of backing stores in the basic DAL/DAO/DAOImplementation pattern. The full pattern is implemented in the `core/dal` for one type of object of common use (Users) and for one store (MongoDB). But the idea is easily borrowed and modified. For instance the Contextual Bandit and Navigation Hinting Engines extend this pattern for their own collections. If the Engine uses a store supported by Harness then it can inject the implementation so that Harness and all of its Engines can target different stores using config to define which is used for any installation of Harness. This uses ScalDI for lightweight dependency injection.
 - **DAL**: The Data Access Layer is a very lightweight way of accessing some external persistence. The knowledge of how to use the store is contained in anything that uses is. The DAL contains an abstract API in the DAO, and implemented in a DAOImpl. There may be many DAOImpls for many stores, the default is MongoDB but others are under consideration.
 - **Administrator**: Each of the above Classes is extended by a specific Engine and the Administrator handles CRUD operations on the Engine instances, which in turn manage there constituent Algorithms and Datasets. The Administrator manages the state of the system through persistence in a scalable DB (default is MongoDB) as well as attaching the Engine to the input and query REST endpoints corresponding to the assigned resource-id for the Engine instance.
 - **Parameters**: are JSON descriptions of Engine instance initialization information. They may contain information for the Administrator, Engine, Algorithm, and Dataset. The first key part of information is the Engine ID, which is used as the unique resource-id in the REST API. The admin CLI may take the JSON from some json file when creating a new engine or re-initializing one but internally the Parameters are stored persistently and only changed when an Engine instance is updated or re-created. After and Engine instance is added to Harness the params are read from the metadata in the shared DB.

From the point of view of a user of Harness (leaving out installation for now) from the very beginning of operation one would expect to perform the following steps:

 1. Start Harness, this will automatically startup any previously added Engines, making them ready for input and queries and/or training on the REST endpoints addressed by their resource-id (called an engine-id in most of our documentation). They are re-initialized using the config date they were created with or last updated with.
 2. In the case of creating a new Engine instance, ask the Administrator to create an Engine given a JSON file of parameters and a Jar containing code for the Engine. The JSON will contain a string which is the classname of an Engine Factory object, which is able to create an Engine instance that can consume the rest of the JSON.
 3. Harness manages a set of endpoints for the new Engine instance attached to the resource-id provided in the JSON Parameters. The Engine instances are typically independent, maintaining their own dataset and models.
 4. At some point an Engine may need to be deleted, which removes the Engine instance's dataset, created from input, and  the model, derived from input, which is used to return query results.
 5. If Harness is to be shutdown, it may be killed by pid or asked to shut itself down with CLI.

## Engine Instance Lifecycle
 
  - **create**: take JSON string as input, which can be parsed and validated in a manner specific to the template. This same string is passed to all classes which are in charge of pulling out what they understand **and nothing else**. 
  - **update**: re-interpret the config JSON to re-initialize the Engine instance. The Dataset is unaffected but the next time a model is created it will reflect any config changes. There are several important restrictions on "updates" to Kappa-style online learners so see their documentation. Most Lambda learners like the Universal Recommender can have all config updated.
  - **destroy**: this tries to remove all evidence that the Engine instance ever existed. It removes all state evidence created by the object. It is assumed that after an Engine is "deleted" it's dataset and model are destroyed and cleaned up with the exception of "mirrored" events, which act as an event log and can be used to re-create an Engine instance's state.
  - **input**: Validates parses and processes input Json objects, very similar to PredictionIO events.
  - **predict**: Takes a JSON Query object, parses, validates and returns the results requested.
  - **other lifecycle**: See Engine docs for any further lifecycle extensions (they are rare).

## Creating an Engine

The lifecycle contract allows an Engine developer to choose something like Spark and an algorithm with an implementation to plug into Harness. The developer inherits a REST input and query API, storage and a way to use Spark as a compute engine. It may take only a few lines of code to validate input, store it and wait until it is told to train. Then get the dataset as a Spark DataFrame and pass it to SparkML for model building. When a query comes in the model is used to return results.

In the best case the Engine developer provides only the glue that matches the algorithm implementation to the lifecycle and to use the storage and compute resources.

If a custom algorithm is required, it can be developed without much concern for the operational requirements provided by Harness.
