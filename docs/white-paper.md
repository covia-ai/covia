# Covia White Paper

## Vision

Covia is a protocol for **federated orchestration** AI supply chains at global scale, the "HTTP for AI".

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

Asset metadata is produced by the author of an asset upon asset creation. Normally, this should be automated with the ability for the author to add optional fields if required. 

Relying parties may choose to independently validate claims expressed in the metadata, or alternatively may trust the author. It is important to note that while some metadata can be automatically verified, this is not possible for all forms of metadata, since claims may depend on external information and judgement (e.g. relating to legal IP rights).

### Asset IDs

All assets are logically identified with a unique ID, which is defined to be the cryptographic hash of the asset metadata.

This ensures that every version of every asset can be uniquely identified and verified using its ID alone.

### Venues

A venue is a location that manages assets.

Venues, by design, may differ in a number of important ways:
- The types of compute facilities or services or data services provides
- Physical limitations, such as storage capacity
- Different governance arrangements or access control rules

Typically, venues are aligned with some organisational unit or function that controls sets of related data. An example might be a predictive modelling team that builds AI models based on consumer behavioural data. The venue would be used for storing training data and predictive models as assets, and offering services that allow other parts of the organisation or trusted partners to request pseudonymised output data sets.

### References

Being able to utilise an asset requires two pieces of information:
- The asset ID, which identifies the asset (and the means to verify it)
- A venue which provides access to the asset

In most circumstances, this information may safely be made public: the asset ID is a cryptographic hash which by design conveys no useful information about the asset metadata or content contained within, and the venue should enforce appropriate access control to ensure that the asset cannot be accessed by unauthorised users.

It is common for multiple venues to possess a copy of the same asset. This can be recognised by references that contain the same asset ID. Since assets are immutable and verifiable, obtaining an asset from one venue is equivalent to any other. For efficiency reasons, users should prefer utilising a copy of an asset in a local venue if it is already available. 

References are possible in multiple formats:

```
// Covia protocol scheme:

cdp:v/mycompany.analytics.venue-101/a/0c1aee860be175d66152388a6513fd4fa11449c1612cbd04dca92ec92e3d0cca


// Standard Web URL:

https://data.mycompany.com/cdp/api/v/v/mycompany.analytics.venue-101/a/0c1aee860be175d66152388a6513fd4fa11449c1612cbd04dca92ec92e3d0cca


// JSON reference:

{
  venue: "mycompany.analytics.venue-101",
  asset: "0c1aee860be175d66152388a6513fd4fa11449c1612cbd04dca92ec92e3d0cca"
}

// DLFS Drive path

dlfs://drive.mycompany.com/venue-assets/101/0c1aee860be175d66152388a6513fd4fa11449c1612cbd04dca92ec92e3d0cca 

// Note: technically a DLFS drive may not be a complete venue, but it can host assets and metadata and drivers
// may treat therefore utilise it as a venue
```

### Agents

An agent is a system component that supports the Covia protocol, and can respond to protocol requests. As such, it represents the "server" aspects of a Covia enabled system in the client/server model.

Typically, an agent represents one or more venues, under the control and governance of a single organisation. An organisation may operate many agents, perhaps representing different functions, geographies or IT infrastructure domains.

Agents provide access to back-end systems such as storage, GPU compute clusters, databases or enterprise services. This role is critical, because it allows existing data assets and infrastructure to be harnessed in the Covia ecosystem. There is significant economic and strategic value in the fact that agents can enable access to such resources *without* requiring significant changes to existing systems.

Assets may be exposed as either:
- A pure data asset - this is likely to be most appropriate for immutable data, e.g. a specific version of a file or data set
- An operation, which allows dynamic / real-time access to an underlying data source - this is appropriate for dynamically changing data where an higher level process may need to acquire the latest version

Agents SHOULD implement access control appropriate to the venues they represent. There is a spectrum of possible levels of access:
- Fully open (free public data, information commons)
- Public fee-based (subscription models, tokenised payments etc.)
- Trusted (research collaborations, trusted business parters)
- Internal (authorised teams within organisations)
- Restricted (no access to most assets except through highly controlled operations)

Covia provides opens source reference implementations for agents, however any ecosystem participant is free to develop their own custom agents providing these follow the standard protocol.

### Drivers

A driver is a software module used to access assets and venues via the Covia protocol. Typically this is available as a software library for developers or a plug-in to enterprise software systems. 

A driver is the client component of a Covia based system, and communicates directly with agents.

Drivers MUST offer a set of standard functionality, most importantly:
- Resolve standard references to an asset / venue
- Retrieve / query an existing asset (or its metadata)
- Create an asset (with metadata)
- Upload an asset (to a venue which authorises this)
- Invoke a compute service

Additional, drivers MAY offer additional functionality as extensions
- Ability to manage access control / governance for specific venues
- Ability to create a temporary working venue for short term collaboration / development purposes
- Advanced search capabilities

Driver developers are encouraged to innovate around driver extensions, but significant new developments SHOULD be standardised to help facilitate interoperability and ultimately allow some extensions to become part of the core protocol. 


### Operations

An operation is a special form of data asset that can be *invoked*.

An operation may define a number of inputs, and a number of outputs. These may be either:
- Assets
- Values encoded as UTF-8 strings

An operation can be considered similar to a web service, with the important additional capabilities:
- Its inputs can include assets anywhere in the Covia ecosystem (since these can be referenced, and accessed using a driver)
- Its outputs can include the creation of new assets, which can therefore be consumed by subsequent operations

Agents which are capable of 

### Orchestration

Orchestration is the execution of arbitrary graphs of operations across arbitrary sets of participants.

This is possible because of the features of the Covia protocol that ensure data assets and operations are designed behave in a standardised way, and can be composed to build higher level processes.

As it is an operation, an orchestration can define arbitrary inputs and outputs. These are passed to underlying operations as required.

Orchestration is executed by agents, which must also normally include a driver (in order to access input assets if required). 

Orchestration agents may impose appropriate access controls, as with any agent. The orchestration agent will also usually require appropriate authorisation to execute the underlying operations.

An orchestration MAY be considered as an operation in its own right, and hence used as a composable building block to create a higher level orchestration.

It is possible for an orchestration to include assets which are not directly accessible to other parties in the orchestration. This capability is particularly important when access to some assets may be highly restricted (e.g. patient medical records) and it is necessary to send compute operations to the data to be executed in a secure trusted execution environment.

It is possible for any party to validate orchestration results by checking that metadata of output assets corresponds to the orchestration execution graph and any inputs used. This creates an automatic, verifiable provenance chain for any AI process, even where this spans multiple parties. This validation process involves several key steps:

**Validation of Orchestration Results**

1. **Metadata Verification**: The metadata associated with each output asset contains detailed information about the orchestration execution graph and the inputs utilized. By examining this metadata, parties can trace back the operations and data sources involved in producing the output.

2. **Execution Graph Analysis**: The orchestration execution graph provides a comprehensive map of the operations performed during the AI process. This includes the sequence of steps, the specific algorithms or models applied, and any intermediate data transformations. By analyzing this graph, parties can verify the procedural integrity of the orchestration.

3. **Input Correlation**: The metadata records the inputs used in the orchestration process. By comparing these recorded inputs with the actual data used, parties can ensure the correctness of the data flow. This step is crucial for confirming that the outputs are based on accurate and intended inputs.

4. **Consistency Checks**: To further ensure the validity of the orchestration results, additional consistency checks can be performed. These checks compare the outputs against expected patterns or benchmarks, identifying any discrepancies that may indicate errors or anomalies.

By following these validation steps, stakeholders can enhance trust and transparency in AI orchestration processes. This robust validation framework ensures that the orchestration results are not only accurate but also auditable, thereby reinforcing confidence in the AI system’s outputs.


### Lattice technology

A lattice is a mathematical, algebraic structure with important properties: They can be used to form conflict-free replicated data types that automatically converge to consensus. This is a powerful tool for distributed systems, as it allows for consensus to be reached without locking.

The Covia protocol makes use of lattice technology in several areas:
- For execution of distributed operations required in orchestration
- For P2P transmission and replication of large verifiable merkle tree based data structures (including DLFS drive)
- For the consensus mechanism of the Convex public virtual machine (used optionally for smart contracts and decventralised tokenisation of AI services)




