# Covia White Paper

THIS IS A DRAFT OF THE COVIA WHITE PAPER FOR DISCUSSION ONLY. NOT TO BE CONSIDERED FINAL.

## Vision

Covia is a protocol for **federated orchestration** AI supply chains at global scale, the **"HTTP for AI"**.

The need for Covia is critical: all organisations will need to adopt AI to secure their competitive advantage in the digital economy of the future. However, most organisations do not have the capabilities to do so. Data assets, skills, compute resources, governance are all essential but only a few large organisations possess all of these assets and the ability to apply them consistently at scale.

With Covia, organisations of all sizes can orchestrate powerful AI supply chains, harnessing the best resources available in a global ecosystem, using open standard Internet technology.

## Key Concepts

### Assets

Covia is designed around the concept of a Universal Data Asset (UDA), a model that allows for the representation of *any* compute resource or data set in a standard form.

An asset is defined the following:
- Immutable metadata that describes the asset
- Asset Content, defined by the type of asset

Assets are **immutable**. It's possibly to modify assets, but this always creates a new asset version. This property is critical for multiple reasons:
- Support for full cryptographic verification of asset integrity
- Support trusted acquisition of assets from decentralised sources using cryptographic hashes as content-addressable IDs
- Allow reliable distributed caching and replication (immutable data never becomes stale....)

Examples of assets:
- A training data set used for model building
- A set of records produced from a production system being sent for real-time processing
- A compute service backed by a GPU system
- A Data access service providing extracts

### Metadata

Asset metadata describes assets in their entirety.

Asset metadata may include:
- Human readable description of an asset
- Authorship information
- License for use (e.g. Creative Commons)
- Cryptographic hashes for content (allowing verification and content acquisition)
- Asset provenance (e.g. references to input data sets used for model training etc.)

Asset metadata is produced by the author of an asset upon asset creation. Typically, this can be automated with the ability for the author to add optional fields if required. 

Relying parties may choose to independently validate claims expressed in the metadata, or alternatively may trust the author. It is important to note that while some metadata can be automatically verified, this is not possible for all forms of metadata, since claims may depend on external information and judgement (e.g. relating to legal IP rights).

### Asset IDs

All assets are logically identified with a unique ID.

The ID is defined to be the cryptographic hash of the asset metadata. By also including the hash(es) or any relevant content within the metadata, this effectively forms the root of a Merkle tree, such that all metadata and content can be recursively verified. This also means that the ID can be used to locate the metadata in **content-addressable storage**.

This scheme of identifying assets by a cryptographic ID ensures that every version of every asset can be uniquely identified and verified using its ID alone.

### Venues

A venue is a logical location that manages assets.

Typically, venues are aligned with some organisation or group within an organisation that controls sets of related data. An example might be a predictive modelling team that builds AI models based on consumer behavioural data. The venue would be used for storing training data and predictive models as assets, and offering services that allow other parts of the organisation or trusted partners to request pseudonymised output data sets.

Venues, by design, may differ in a number of important ways:
- The types of compute facilities or services or data services provides
- Physical limitations, such as storage capacity
- Different governance arrangements or access control rules
- Scale can be anything from a single network connected server up to a global virtual network spanning multiple data centres


### References

Utilising an asset through the protocol requires two pieces of information:
- The asset ID, which identifies the asset (and the means to verify it)
- A venue which provides access to the asset

Together, this information is a "reference" to an asset.

In most circumstances, this information may safely be made public: the asset ID is a cryptographic hash which by design conveys no useful information about the asset metadata or content contained within, and the venue should enforce appropriate access control to ensure that the asset cannot be accessed by unauthorised users.

It is possible for multiple venues to possess a copy of the same asset. This can be recognised by references that contain the same asset ID. Since assets are immutable and verifiable, obtaining an asset from one venue is equivalent to any other. For efficiency reasons, users should prefer utilising a copy of an asset in a local venue if it is already available. 

References are possible in multiple formats:

```
// Covia protocol scheme:

cdp:v/mycompany.analytics.venue-101
  /a/0c1aee860be175d66152388a6513fd4fa11449c1612cbd04dca92ec92e3d0cca


// Standard Web URL:

https://data.mycompany.com/cdp/api/v1/v/mycompany.analytics.venue-101
  /a/0c1aee860be175d66152388a6513fd4fa11449c1612cbd04dca92ec92e3d0cca


// JSON reference:

{
  venue: "mycompany.analytics.venue-101",
  asset: "0c1aee860be175d66152388a6513fd4fa11449c1612cbd04dca92ec92e3d0cca"
}

// DLFS Drive path

dlfs://drive.mycompany.com/venue-assets
   /101/0c1aee860be175d66152388a6513fd4fa11449c1612cbd04dca92ec92e3d0cca 

// Note: technically a DLFS drive need not be a complete venue, but it can 
// host assets and metadata and drivers may therefore utilise it as a venue
```

### Agents

An agent is a system component that supports the Covia protocol and can respond to protocol requests. As such, it represents the "server" aspects of a Covia enabled system in the client/server model.

Typically, an agent represents one or more venues, under the control and governance of a single organisation. An organisation may operate multiple agents, perhaps representing different functions, geographies or IT infrastructure domains.

Agents provide access to back-end systems such as storage, GPU compute clusters, databases or enterprise services. This role is critical, because it allows existing data assets and infrastructure to be harnessed in the Covia ecosystem. There is significant economic and strategic value in the fact that agents can enable access to such resources *without* requiring significant changes to existing systems.

Assets may be exposed as either:
- A pure data asset - this is likely to be most appropriate for immutable data, e.g. a specific version of a file or data set.
- An operation, which allows dynamic / real-time access to an underlying data source - this is appropriate for dynamically changing data where a higher level process may need to acquire the latest version.

Agents SHOULD implement access control appropriate to the venues they represent. There is a spectrum of possible levels of access:
- Fully open (free public data, information commons)
- Public fee-based (subscription models, tokenised payments etc.)
- Trusted (research collaborations, trusted business partners)
- Internal (authorised teams within organisations)
- Restricted (no access to most assets except through highly controlled operations)

Covia provides opens source reference implementations for agents, however any ecosystem participant is free to develop their own custom agents providing these follow the standard protocol.

### Drivers

A driver is a software module used to access assets and venues via the Covia protocol. Typically, this is available as a software library for developers or a plug-in to enterprise software systems. 

A driver is the client component of a Covia based system and communicates directly with agents.

Drivers MUST offer a set of standard functionality, most importantly:
- Resolve standard references to an asset / venue
- Retrieve / query an existing asset (or its metadata)
- Create an asset (with metadata)
- Upload an asset (to a venue which authorises this)
- Invoke a compute service

Additional, drivers MAY offer additional functionality as extensions:
- Ability to manage access control / governance for specific venues
- Ability to create a temporary working venue for short term collaboration / development purposes
- Advanced search capabilities

Driver developers are encouraged to innovate around driver extensions, but significant new developments SHOULD be standardised to help facilitate interoperability and ultimately allow some extensions to become part of the core protocol. 

### Operations

An operation is a special form of data asset that can be *invoked*. A typical operation might be training a model, anonymising a data set, or producing model output in response to a user request.

An operation may define a number of inputs, and a number of outputs. These may be either:
- Assets
- Values encoded as UTF-8 strings

An operation can be considered similar to a web service, with the important additional capabilities:
- Its inputs can include assets anywhere in the Covia ecosystem (since these can be referenced, and accessed using a driver)
- Its outputs can include the creation of new assets, which can therefore be consumed by subsequent operations

Agents may be configured to expose internal services or API functions accessible locally as an operation. By this mechanism, agents make such services available to the broader ecosystem in an interoperable manner, while being able to enforce any additional security checks and access controls that may be required.

### Orchestration

Orchestration is the execution of arbitrary graphs of operations across arbitrary sets of participants.

This is possible because of the features of the Covia protocol that ensure data assets and operations are designed to behave in a standardised way and can be composed to build higher level processes.

As it is an operation, an orchestration can define arbitrary inputs and outputs. These are passed to underlying operations as required.

Orchestration is executed by agents, which must also normally include a driver (in order to access input assets if required). 

Orchestration agents may impose appropriate access controls, as with any agent. The orchestration agent will also usually require appropriate authorisation to execute the underlying operations.

An orchestration MAY be considered as an operation in its own right, and hence used as a composable building block to create a higher-level orchestration.

It is possible for an orchestration to include assets which are not directly accessible to other parties in the orchestration. This capability is particularly important when access to some assets may be highly restricted (e.g. patient medical records), and it is necessary to send compute operations to the data to be executed in a secure trusted execution environment.

It is possible for any party to validate orchestration results by checking that metadata of output assets corresponds to the orchestration execution graph and any inputs used. This creates an automatic, verifiable provenance chain for any AI process, even where this spans multiple parties. This validation process involves several key steps:

**Validation of Orchestration Results**

1. **Metadata Verification**: The metadata associated with each output asset contains detailed information about the orchestration execution graph and the inputs utilised. By examining this metadata, parties can trace back the operations and data sources involved in producing the output.

2. **Execution Graph Analysis**: The orchestration execution graph provides a comprehensive map of the operations performed during the AI process. This includes the sequence of steps, the specific algorithms or models applied, and any intermediate data transformations. By analysing this graph, parties can verify the procedural integrity of the orchestration.

3. **Input Correlation**: The metadata records the inputs used in the orchestration process. By comparing these recorded inputs with the actual data used, parties can ensure the correctness of the data flow. This step is crucial for confirming that the outputs are based on accurate and intended inputs.

4. **Consistency Checks**: To further ensure the validity of the orchestration results, additional consistency checks can be performed. These checks compare the outputs against expected patterns or benchmarks, identifying any discrepancies that may indicate errors or anomalies.

By following these validation steps, stakeholders can enhance trust and transparency in AI orchestration processes. This robust validation framework ensures that the orchestration results are not only accurate but also auditable, thereby reinforcing confidence in the AI system’s outputs.

### Lattice technology

A lattice is a mathematical, algebraic structure with important properties: They can be used to form conflict-free replicated data types that automatically converge to consensus. This is a powerful tool for distributed systems, as it allows for consensus to be reached without locking.

The Covia protocol makes use of lattice technology in several areas:
- For execution of distributed operations required in orchestration
- For P2P transmission and replication of large verifiable Merkle tree based data structures (including DLFS drive)
- For the consensus mechanism of the Convex public virtual machine (used optionally for smart contracts and decentralised tokenisation of AI services)

### Open Ecosystem

The protocol enables an open, peer-to-peer ecosystem of service providers offering an unlimited variety of venues, assets and services. It is an open standard precisely because this is the best model to enable an effective ecosystem.

As such, the protocol is flexible and can support any business model chosen by participants. We anticipate:
- Collaboration between trusted organisations on a contractual basis
- Services offered publicly on a pay-as-you-go basis
- Marketplaces of assets with licenses for sale
- A wide variety of free data assets open to all

It is possible to configure agents to allow access to any existing data assets via the Covia protocol, so that existing data ecosystems are opened up to Covia users in a standard, interoperable way. As such, the protocol serves as a "bridge" between different islands of public and private data. 

## Solution Roadmap

The Covia roadmap is based around building interoperable components that implement the Covia protocol.

Many of these will be made available as open source reference implementations, that anyone can user freely or customise for their project. Companies in the ecosystem are free to offer commercially supported versions of these or create their own solutions as long as they remain consistent with the open standard protocol.

### Universal Protocol Toolbox

The protocol toolbox provides software, tools, and documentation for developers building projects using Covia. 

#### Driver Libraries

Driver libraries are software libraries designed to allow developers to embed the Covia protocol into their products and solutions, while continuing to use their existing preferred tools and programming languages. In effect, they can add a Covia driver to their software with just a few lines of code.

The driver libraries provide client support for all Covia protocol operations. Important examples include:
- Create and validate metadata
- Publish / upload an asset to a venue
- Execute an operation (which could be a complete orchestration) 
- Read asset metadata 
- Download asset content

We will initially target three major language ecosystems widely used in the enterprise software / data / AI space:
- Python
- Javascript
- Java

#### Reference Agents

Reference agents are software components that serve as a Covia protocol agent. These are designed to be configured and operated on standard commodity servers, e.g. running Linux. Standard agent features include:
- Storage on local file systems
- Metadata management
- REST API server endpoints
- Access control mechanisms
- Ability to invoke operations
- Ability to proxy and relay operations to other agents
- Ability to perform decentralised functions on the Convex network

The agents are readily customisable, especially with respect to allowing developers to implement their own custom operations or access control rules.

#### Enterprise adapters

Enterprise adapters are agents that connect to existing systems and allow data assets and services to be utilised via the Covia protocol. 

Key enterprise adapters will include:
- AI frameworks (e.g. LangChain)
- Enterprise storage systems
- SQL / ODBC databases
- NoSQL databases
- GPU / compute clusters
- Enterprise Service Busses and middleware platforms
- Enterprise application software (e.g. SAP)

Similar to the reference agents, enterprise adapters offer the ability to customise access control rules. This may be used to offer an additional layer of security on top of the security implemented in the underlying enterprise systems.

#### Standard Documents

The Covia protocol will be documented and maintained as a public open standard and as a reference for developers.

### Orchestration Engine

The Orchestration Engine is an advanced Covia agent that supports the execution of orchestration graphs.

Key functionality includes:
- Managing a database of orchestration graphs
- Executing specific graphs on demand and reporting results
- Delegating operations within the graph to other Covia agents (possibly remote, or managed in a venue provided by a different organisation)
- Customisable logging and access controls
- Making entire orchestrations available as composable operations for others to re-use 

### Data Lattice File System (DLFS)

The Data Lattice File System is a powerful decentralised data storage system using Lattice Technology - "Dropbox meets IPFS meets BitTorrent". 

#### DLFS Browser

DLFS Browser is a GUI tool for exploring and managing filesystems on DLFS (local or remote). 

In addition to standard file management capabilities, the Browser incorporates an embedded driver and tools to interact directly with the Covia protocol, e.g. publishing a DLFS file as a data asset on a venue or executing an operation with a given file as input.

#### DLFS Node

The DLFS Node is a back-end storage node for DLFS. This node runs on a server (typically internet connected) and replicates subsets of the DLFS lattice with its peers.

The DLFS Node also serves as a Covia agent and can be configured to make DLFS based assets accessible to protocol users as a venue with customisable access control rules. 

### Decentralised Vector Database

Lattice Technology offers the intriguing possibility of a truly decentralised vector database, allowing the distributed storage, search and retrieval of vector data. Such vector databases are likely to become increasingly important with the demand for AI systems to use data based on vector embeddings.

This solution is based on combining several important technologies:
- Lattice Technology for replication and validation
- Kademlia-style P2P routing
- Locality-sensitive hashing (LSH)
- Vector similarity search

Decentralised vector database options can also be used directly with the Covia protocol, because database nodes are themselves Covia agents! So a vector similarity search can easily be included in a Covia based orchestration as part a larger AI process pipeline.

## The Covia Token

The Covia Token is a decentralised digital currency for the data economy.

Use of the Covia token is **optional** - The protocol can be operated freely on a P2P basis without any usage of the token. Collaborating organisations may simply choose to utilise each other's venues directly. 

However the token will provide a number of important additional capabilities: 
- Trade and monetise data assets using decentralised smart contracts
- Create immutable, public provenance records
- Register venues to establish a public root of trust
- Accept payments in tokens, e.g. pre-payment for metered services
- Allow for decentralised governance mechanisms, such as dispute arbitration

The Covia Token is a CAD29 fungible token running on the Convex protonet / main network. The lattice technology powering Convex makes it extremely well suited as the basis for a decentralised data economy.


 
