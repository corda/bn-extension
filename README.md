# Business networks and memberships

The Corda platform extension for creating and managing business networks allows a node operator to define and create a logical network based on a set of common CorDapps as well as a shared
business context. Corda nodes outside of a business network are not aware of its members. The network can be split into subgroups or membership lists which allows for further privacy (members of a group
only know about those in their group).

In a business network, there is at least one *authorised member*. This member has sufficient permissions to execute management operations over the network and its members.

## Installation

Add the `business-networks-contracts` dependency in your "contracts" (and states) cordapp module:

```groovy
dependencies {
    //...
    cordapp("net.corda.bn:business-networks-contracts:$corda_bn_extension_version")
    //...
}
``` 

... and the `business-networks-workflows` dependency in your "workflows" cordapp module:

```groovy
dependencies {
    //...
	cordapp("net.corda.bn:business-networks-workflows:$corda_bn_extension_version")
    //...
}
```

Remember to add both dependencies in your Cordform (i.e. `deployNodes`) task.

## Creating and managing a business network

BNE provides a set of workflows that allows a user to start a business network, on-board members and assign them to membership lists or groups. The flows can also be used to update the information
in the memberships (update business network identity, modify member roles) and manage them (suspend or revoke members).

### Create a business network

Either from the node shell or from an RPC client, run `CreateBusinessNetworkFlow`. This will self issue a membership with an exhaustive permissions set that will
allow the calling node to manage future operations for the newly created network.

**Flow arguments:**

- ```networkId``` Custom ID to be given to the new Business Network. If not specified, a randomly selected one will be used
- ```businessIdentity``` Optional custom business identity to be given to membership
- ```groupId``` Custom ID to be given to the initial Business Network group. If not specified, randomly selected one will be used
- ```groupName``` Optional name to be given to the Business Network group
- ```notary``` Identity of the notary to be used for transactions notarisation. If not specified, first one from the whitelist will be used

*Example*:
```kotlin
val myIdentity: BNIdentity = createBusinessNetworkIdentity() // mock method that creates an instance of a class implementing [BNIdentity]
val groupId = UniqueIdentifier()
val notary = serviceHub.networkMapCache.notaryIdentities.first()

subFlow(CreateBusinessNetworkFlow("MyBusinessNetwork", myIdentity, groupId, "Group 1", notary))
```

### On-board a new member

Joining a business network is a 2 step process. First, the Corda node wishing to join must run the ```RequestMembershipFlow``` either from the node shell or any other RPC client.
As a result of a successful run, a membership is created with a *PENDING* status and all authorised members will be notified of any future operations involving it. Until activated
by an authorised party (a business network operator for instance) the newly generated membership can neither be used nor grant the requesting node any permissions in the business network.

**RequestMembershipFlow arguments**:

- ```authorisedParty``` Identity of authorised member from whom membership activation is requested
- ```networkId``` ID of the Business Network that potential new member wants to join
- ```businessIdentity``` Optional custom business identity to be given to membership
- ```notary``` Identity of the notary to be used for transactions notarisation. If not specified, first one from the whitelist will be used

*Example*:

```kotlin
val myIdentity: BNIdentity = createBusinessNetworkIdentity() // create an instance of a class implementing [BNIdentity]
val networkId = "MyBusinessNetwork"
val bno: Party = ... // get the [Party] object of the Corda node acting as a BNO for the business network represented by [networkId]
val notary = serviceHub.networkMapCache.notaryIdentities.first())

subFlow(RequestMembershipFlow(bno, networkId, myIdentity, notary))
```

To finalise the on-boarding process, an authorised party needs to run the ```ActivateMembershipFlow```. This will update the targeted membership status from *PENDING* to *ACTIVE* after
signatures are collected from **all** authorised parties in the network. After activation, the activating party needs to follow-up with a group assignment by running the ```ModifyGroupFlow```.

**ActivateMembershipFlow arguments**:

- ```membershipId``` ID of the membership to be activated
- ```notary``` Identity of the notary to be used for transactions notarisation. If not specified, first one from the whitelist will be used

*Example*:

```kotlin
val bnService = serviceHub.cordaService(BNService::class.java)
val networkId = "MyBusinessNetwork"
val newMemberPartyObject = ... // get the [Party] object of the member whose membership is being activated
val membershipId = bnService.getMembership("MyBusinessNetwork", newMemberPartyObject)
val groupName = "Group 1"
val groupId = ... // identifier of the group which the member will be assigned to
val notary = serviceHub.networkMapCache.notaryIdentities.first()

subFlow(ActivateMembershipFlow(membershipId, notary))

// add newly activated member to a membership list
val newParticipantsList = bnService.getBusinessNetworkGroup(groupId).state.data.participants.map {
    BNService.getMembership(networkId, it)!!.state.data.linearId
} + membershipId

subFlow(ModifyGroupFlow(groupId, groupName, newParticipantsList, notary))
```

Onboarding new member can also be done without prior membership request. Authorised party needs to run `OnboardMembershipFlow` 
to **directly** issue a new membership with ACTIVE status. After onboarding, authorised member also needs to follow-up with a 
group assignment by running the `ModifyGroupFlow`.

**OnboardMembershipFlow arguments**:

- `networkId` ID of the Business Network that member is onboarded to
- `onboardedParty` Identity of an onboarded member
- `businessIdentity` Custom business identity to be given to the onboarded membership
- `notary` Identity of the notary to be used for transactions notarisation. If not specified, first one from the whitelist will be used

*Example*:

```kotlin
val bnService = serviceHub.cordaService(BNService::class.java)
val networkId = "MyBusinessNetwork"
val onboardedParty = ... // get the [Party] object of the Corda node acting as an onboarded member
val businessIdentity: BNIdentity = createBusinessNetworkIdentity() // create an instance of a class implementing [BNIdentity]
val notary = serviceHub.networkMapCache.notaryIdentities.first()
val groupId = ... // identifier of the group which the member will be assigned to
val groupName = "Group 1"

subFlow(OnboardMembershipFlow(networkId, onboardedParty, businessIdentity, notary))

// add newly activated member to a membership list
val membershipId = bnService.getMembership(networkId, onboardedParty)!!.state.data.linearId
val newParticipantsList = bnService.getBusinessNetworkGroup(groupId).state.data.participants.map {
    BNService.getMembership(networkId, it)!!.state.data.linearId
} + membershipId

subFlow(ModifyGroupFlow(groupId, groupName, newParticipantsList, notary))
```

### Amend a membership

There are several ways in which a member's information can be updated, not including network operations such as membership suspension or revocation. These attributes which can be amended are:
business network identity, membership list or group, and roles.

To update a member's business identity attribute, one of the authorised network parties needs to run the ```ModifyBusinessIdentityFlow``` which then requires all network members with
sufficient permissions to approve the proposed change.

**ModifyBusinessIdentityFlow arguments**:

- ```membershipId``` ID of the membership to modify business identity
- ```businessIdentity``` Optional custom business identity to be given to membership
- ```notary``` Identity of the notary to be used for transactions notarisation. If not specified, first one from the whitelist will be used

*Example*:

```kotlin
val bnService = serviceHub.cordaService(BNService::class.java)
val networkId = "MyBusinessNetwork"
val partyToBeUpdated = ... // get the [Party] object of the member being updated
val membership = bnService.getMembership(networkId, partyToBeUpdated)
val updatedIdentity: BNIdentity = updateBusinessIdentity(membership.state.data.identity) // mock method that updates the business identity in some meaningful way
val notary = serviceHub.networkMapCache.notaryIdentities.first())

subFlow(ModifyBusinessIdentityFlow(membership.state.data.linearId, updatedIdentity, notary))
```

Updating a member's roles and permissions in the business network is done in a similar fashion by using the ```ModifyRolesFlow```. Depending on the proposed changes, the updated member may become
an authorised member. If that happens, an important thing to note is that this enhancement will have to be preceded by an execution of the ```ModifyGroupsFlow``` to add the member to all membership
lists it will have administrative powers over.

**ModifyRolesFlow arguments**:

- ```membershipId``` ID of the membership to assign roles
- ```roles``` Set of roles to be assigned to membership
- ```notary``` Identity of the notary to be used for transactions notarisation. If not specified, first one from the whitelist will be used

*Example*:

```kotlin
val roles = setOf(BNORole()) // assign full permissions to member
val bnService = serviceHub.cordaService(BNService::class.java)
val networkId = "MyBusinessNetwork"
val partyToBeUpdated = ... // get the [Party] object of the member being updated
val membershipId = bnService.getMembership(networkId, partyToBeUpdated).state.data.linearId
val notary = serviceHub.networkMapCache.notaryIdentities.first())

subFlow(ModifyRolesFlow(membershipId, roles, notary))
```

To manage the membership lists or groups, one of the authorised members of the network can use ```CreateGroupFlow```, ```DeleteGroupFlow``` and ```ModifyGroupFlow```.

**CreateGroupFlow arguments**:

- ```networkId``` ID of the Business Network that Business Network Group will relate to
- ```groupId``` Custom ID to be given to the issued Business Network Group. If not specified, randomly selected one will be used
- ```groupName``` Optional name to be given to the issued Business Network Group
- ```additionalParticipants``` Set of participants to be added to issued Business Network Group alongside initiator's identity
- ```notary``` Identity of the notary to be used for transactions notarisation. If not specified, first one from the whitelist will be used

**Example**:

```kotlin
val notary = serviceHub.networkMapCache.notaryIdentities.first())
val myNetworkId = "MyBusinessNetwork"
val myGroupId = UniqueIdentifier()
val groupName = "Group 1"

subFlow(CreateGroupFlow(myNetworkId, myGroupId, groupName, emptySet(), notary))
```

**DeleteGroupFlow arguments**:

- ```groupId``` ID of group to be deleted
- ```notary``` Identity of the notary to be used for transactions notarisation. If not specified, first one from the whitelist will be used

The ```ModifyGroupFlow``` can update the name of a group and/or its list of members. At least one of the *name* or *participants* arguments
must be provided.

**ModifyGroupFlow arguments**:

- ```groupId``` ID of group to be modified
- ```name``` New name of modified group
- ```participants``` New participants of modified group
- ```notary``` Identity of the notary to be used for transactions notarisation. If not specified, first one from the whitelist will be used

*Example*:

```kotlin
val bnService = serviceHub.cordaService(BNService::class.java)
val bnGroupId = ... // get the identifier of the group being updated
val bnGroupName = bnService.getBusinessNetworkGroup(bnGroupId).state.data.name
val participantsList = bnService.getBusinessNetworkGroup(bnGroupId).state.data.participants
val newParticipantsList = removeMember(someMember, participantsList) // mock method that removes a member from the group
val notary = serviceHub.networkMapCache.notaryIdentities.first())

subFlow(ModifyGroupFlow(bnGroupId, bnGroupName, newParticipantsList, notary))
```

### Suspend or revoke a membership

Temporarily suspending a member or completely removing it from the business network is done using ```SuspendMembershipFlow``` and ```RevokeMembershipFlow```. They both use the same exactly arguments:

- ```membershipId``` ID of the membership to be suspended/revoked
- ```notary``` Identity of the notary to be used for transactions notarisation. If not specified, first one from the whitelist will be used

Suspending a member will result in a membership status change to ```SUSPENDED``` and still allow said member to be in the business network. Revocation means that the membership is marked as historic/spent
and and a new one will have to be requested and activated in order for the member to re-join the network.

*Example*:

```kotlin
val notary = serviceHub.networkMapCache.notaryIdentities.first())
val memberToBeSuspended = ... // get the linear ID of the membership state associated with the Party which is being suspended from the network
val memberToBeRevoked = ... // get the linear ID of the membership state associated with the Party which is being removed from the network

// Revocation
subFlow(RevokeMembershipFlow(memberToBeRevoked, notary))

// Suspension
subFlow(SuspendMembershipFlow(memberToBeSuspended, notary))
```

### Request membership attribute changes

Using the ```RequestMembershipAttributeChangeFlow``` flow a member can create requests in order to change its attributes (business identity and roles).
Note that when you request for new roles the changes will overwrite your existing roles.
This flow will create a ```ChangeRequestState``` with ```PENDING``` status.

After the request creation an authorised member is able to decline the changes with using ```DeclineMembershipAttributeChangeFlow```
or accept using ```ApproveMembershipAttributeChangeFlow```.
If you decline the request the existing```ChangeRequestState``` will have ```DECLINED``` status.
If you accept the request the existing```ChangeRequestState``` will have ```ACCEPTED``` status.
There is also an option with ```DeleteMembershipAttributeChangeRequestFlow``` which marks these request as consumed to avoid stockpiling them in the database.

**RequestMembershipAttributeChangeFlow arguments**:

- ```authorisedParty``` Identity of authorised member from whom the change request approval/rejection is requested.
- ```networkId``` ID of the Business Network that members are part of.
- ```businessIdentity``` The proposed business identity change.
- ```roles``` The proposed role change.
- ```notary``` Identity of the notary to be used for transactions notarisation. If not specified, first one from the whitelist will be used.

*Example*:

```kotlin
val authorisedParty: Party = ... // get the [Party] object of the authorised Corda node
val networkId = "MyBusinessNetwork"
val updatedIdentity: BNIdentity = ... // the new business identity you want to associate the member with, if you don't want to modify your existing business identity, then simply skip this step
val updatedRoles: Set<BNRole> = ... // the new roles you want to associate the member with, if you don't want to modify your existing roles, then simply skip this step
val notary = serviceHub.networkMapCache.notaryIdentities.first()

// Request creation
subFlow(authorisedParty, networkId, updatedIdentity, updatedRoles, notary)
```

**ApproveMembershipAttributeChangeFlow arguments**:

- ```requestId``` The ID of the request which needs to be accepted.
- ```notary``` Identity of the notary to be used for transactions notarisation. If not specified, first one from the whitelist will be used.

*Example*:

```kotlin
val requestId = ... // get the linear ID of the change request state associated with the Party which is requesting for attribute changes
val notary = serviceHub.networkMapCache.notaryIdentities.first()

// Approves request
subFlow(ApproveMembershipAttributeChangeFlow(requestId, notary))
```

**DeclineMembershipAttributeChangeFlow arguments**:

- ```requestId``` The ID of the request which needs to be rejected.
- ```notary``` Identity of the notary to be used for transactions notarisation. If not specified, first one from the whitelist will be used.

*Example*:

```kotlin
val requestId = ... // get the linear ID of the change request state associated with the Party which is requesting for attribute changes
val notary = serviceHub.networkMapCache.notaryIdentities.first()

// Declines request
subFlow(DeclineMembershipAttributeChangeFlow(requestId, notary))
```

**DeleteMembershipAttributeChangeRequestFlow arguments**:

- ```requestId``` The ID of the request which needs to be consumed.
- ```notary``` Identity of the notary to be used for transactions notarisation. If not specified, first one from the whitelist will be used.

*Example*:

```kotlin
val requestId = ... // get the linear ID of the change request state associated with the Party which is requesting for attribute changes
val notary = serviceHub.networkMapCache.notaryIdentities.first()

// Marks request as CONSUMED
subFlow(DeleteMembershipAttributeChangeRequestFlow(requestId, notary))
```

### Composite Flows

We enhance existing Business Network management flow suite to make Business Network Operator's operations easier 
and more practical than before by introduction of **Composite Flows**. These are flows that call primitive Business Network 
management flows (ones under `net.corda.bn.flows` package). All of them are contained inside `net.corda.bn.flows.composite` 
package.

There are 2 flows released: 
- `BatchActivateMembershipFlow` which activates set of pending membership requests and adds them to specified groups.
- `BatchOnboardMembershipFlow` which onboards set of new memberships and adds them to specific groups.

**BatchActivateMembershipFlow arguments**:

- `memberships` Set of memberships' `ActivationInfo`s
- `defaultGroupId` ID of the group where members are added if the specific group ID is not provided in their `ActivationInfo`
- `notary` Identity of the notary to be used for transactions notarisation. If not specified, first one from the whitelist will be used

*Example*:

```kotlin
val (membershipId1, membershipId2) = ... // fetch pending memberships using [BNService]
val groupForMember1 = ... // get ID of the group where member1 will be added after activation
val memberships = setOf(
    ActivationInfo(membershipId = membershipId1, groupId = groupForMember1),
    ActivationInfo(membershipId = membershipId2, groupId = null)
)
val defaultGroupId = ... // get ID of the group where activated members will be added by default
val notary = serviceHub.networkMapCache.notaryIdentities.first()

subFlow(BatchActivateMembershipFlow(memberships, defaultGroupId, notary))
```

**BatchOnboardMembershipFlow arguments**:

- `networkId` ID of the Business Network where members are onboarded
- `onboardedParties` Set of parties to be onboarded and group where to be added after onboarding
- `defaultGroupId` ID of the group where members are added if the specific group ID is not provided in their `OnboardingInfo`
- `notary`  Identity of the notary to be used for transactions notarisation. If not specified, first one from the whitelist will be used

*Example*:

```kotlin
val networkId = "MyBusinessNetwork"
val (party1, party2) = ... // get parties to be onboarded to the Business Network
val groupForParty1 = ... // get ID of the group where party1 will be added after onboarding
val businessIdentity1 = createBusinessNetworkIdentity() // mock method that creates an instance of a class implementing [BNIdentity]
val onboardedParties = setOf(
    OnboardingInfo(party = party1, businessIdentity = businessIdentity1, groupId = groupForParty1),
    OnboardingInfo(party = party2, businessIdentity = null, groupId = null)
)
val defaultGroupId = ... // get ID of the group where activated members will be added by default
val notary = serviceHub.networkMapCache.notaryIdentities.first()

subFlow(BatchOnboardMembershipFlow(networkId, onboardedParties, defaultGroupId, notary))
```

### Access control report

The Business Network Operator (BNO) is able to ask for the access control report by calling ```BNOAccessControlReportFlow```
and receives the following information in the form of an ```AccessControlReport```. Here are the attributes of
the report file:
- ```members``` A detailed list of the members within the network. It contains the following information:
    - ```cordaIdentity``` The Corda identity of the member.
    - ```businessIdentity``` The business identity of the member.
    - ```membershipStatus``` The current status of the member's membership.
    - ```groups``` List of all the groups member is part of.
    - ```roles``` set of roles the member has.
- ```groups``` A detailed list of the groups within the network. It contains the following information:
    - ```name``` The name of the group.
    - ```participants``` List of participants in the group.

**BNOAccessControlReportFlow arguments**:

- ```networkId``` ID of the Business Network, where the participants are present.
- ```path``` The chosen path for the file to be placed.
- ```fileName``` The chosen file name of the report file.

The ```path``` and ```fileName``` are optional arguments which means that they will have a default value if you don't
define them. In this case the files will be written to the ```user.dir``` under the name of ```bno-access-control-report```.

*Example*:

```kotlin
val networkId = "MyBusinessNetwork"
val path = ... // the absolute path where the report file should be placed
val fileName = ... // the name of the report file

subFlow(BNOAccessControlReportFlow(networkId, path, fileName))
```


### Re-issue states affected by the change to a member's Corda Identity

It may happen that a member in the network needs to re-issue the certificate to which its Corda Identity binds. In that case,
all membership and group states which are impacted should be re-issued with the new Corda Identity. This can be done using the ```UpdateCordaIdentityFlow```. Please note that
this flow requires the legal identity (CordaX500Name) to be the same. Furthermore, the flow can only be run from a member with sufficient permissions(can modify groups).

**If several members of the network have their certificates rotated, it's important to start the identity update process with the authorised members as they are required
to sign all other identity update transactions.**

**UpdateCordaIdentityFlow arguments**:

- ```membershipId``` membershipId ID of the membership whose Corda Identity has changed
- ```notary``` Identity of the notary to be used for transactions notarisation. If not specified, first one from the whitelist will be used

*Example*:

```kotlin
val notary = serviceHub.networkMapCache.notaryIdentities.first())
val updatedMember = ... // get the linear ID of the membership state associated with the Party which was updated

subflow(UpdateCordaIdentityFlow(updatedMember, notary)
```