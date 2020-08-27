# Business Networks Demo

## Overview

This demo showcases integration of Business Networks solution inside a CorDapp designed for issuing and 
settling loans between banks. It brings up 4 nodes: a notary and 3 nodes representing banks. Each bank node 
must be active member of the same Business Network, have a Swift Business Identifier Code (BIC) as their 
business identity and loan issuance initiators must be granted permission to do so.

## Flows

RPC exposed flows can be divided into 2 group: standard Business Network management (covered in greater detail in 
Business Networks documentation) and CorDapp specific ones. 

### CorDapp Specific Flows
- `AssignBICFlow` assigns BIC as business identity of a bank node
    - Usage: `flow start AssignBICFlow membershipId: <UNIQUE_IDENTIFIER>, bic: <STRING>, notary: <OPTIONAL_NOTARY_IDENTITY>`
- `AssignLoanIssuerRoleFlow` grants loan issuance permission to a calling party
    - Usage: `flow start AssignLoanIssuerRoleFlow networkId: <STRING>, notary: <OPTIONAL_NOTARY_IDENTITY>`
- `IssueLoanFlow` issues new loan ledger between caller as lender and borrower specified as flow argument. It also 
  performs verification of both parties to ensure they are active members of Business Network with ID specified as 
  flow argument. Existence of BIC as business identity is checked and whether flow caller has permission to issue loan.
    - Usage: `flow start IssueLoanFlow networkId: <STRING>, borrower: <PARTY>, amount: <INT>`
- `SettleLoanFlow` decreases loan's amount by amount specified as flow argument. If it fully settles the loan, associated 
  state is consumed. It also verifies both parties are active members of a Business Network loan belongs to and that 
  they both have BIC as business identity.
    - Usage: `flow start SettleLoanFlow loanId: <UNIQUE_IDENTIFIER>, amountToSettle: <INT>`
    
## Sample Usage

To deploy and run nodes from the command line in Unix:

1. Run `./gradlew business-networks-demo:deployNodes` to create a set of configs and installs under 
   `business-networks-demo/build/nodes`
2. Run `./business-networks-demo/build/nodes/runnodes` to open up 4 new terminal tabs/windows with 3 bank nodes

To deploy and run nodes from the command line in Windows:

1. Run `gradlew business-networks-demo:deployNodes` to create a set of configs and installs under 
   `business-networks-demo/build/nodes`
2. Run `business-networks-demo\build\nodes\runnodes` to open up 4 new terminal tabs/windows with 3 bank nodes

Next steps are same for every OS (Windows/Mac/Linux).

### Create Business Network Environment

1. Create a Business Network from *Bank A* node by running `flow start CreateBusinessNetworkFlow`
2. Obtain network ID and initial group ID from *Bank A* by running 
   `run vaultQuery contractStateType: net.corda.core.contracts.ContractState` and looking into latest 
   `MembershipState` and `GroupState` issued
3. Request membership from *Bank B* and *Bank C* nodes by running 
   `flow start RequestMembershipFlow authorisedParty: Bank A, networkId: <OBTAINED_NETWORK_ID>, businessIdentity: null, notary: null`
4. Obtain requested membership IDs for *BanK B* and *Bank C* by running 
   `run vaultQuery contractStateType: net.corda.core.contracts.ContractState` on *Bank A* node and looking 
   into `linearId` of newly issued `MembershipState`s.
5. Activate *Bank B* and *Bank C* membership requests from *Bank A* node by running 
   `flow start ActivateMembershipFlow membershipId: <LINEAR_ID>, notary: null` for each requested membership.
6. Add newly activated *Bank B* and *Bank C* members into initial group by running 
   `flow start ModifyGroupFlow groupId: <OBTAINED_GROUP_ID>, name: null, participants: [<BANK_A_ID>, <BANK_B_ID>, <BANK_C_ID>], notary: null` 
   on *Bank A* node.
7. Assign BIC to each of 3 bank nodes by running 
   `flow start AssignBICFlow membershipId: <LINEAR_ID>, bic: <STRING>, notary: null` on *Bank A* node 
   (examples of valid BIC - "BANKGB00", "CHASUS33XXX").
8. Assign Loan Issuer role to *Bank A* by running 
   `flow start AssignLoanIssuerRoleFlow networkId: <OBTAINED_NETWORK_ID>, notary: null` on *Bank A* node
   
### Issue and settle a loan

1. Issue loan from *Bank A* to *Bank B* by running 
   `flow start IssueLoanFlow networkId: <OBTAINED_NETWORK_ID>, borrower: Bank B, amount: 10` on *Bank A* node
2. Obtain loan ID from *Bank B* node by running 
   `run vaultQuery contractStateType: net.corda.bn.demo.contracts.LoanState` and looking into `linearId`
3. Settle loan by running `flow start SettleLoanFlow loanId: <OBTAINED_LOAN_ID>, amountToSettle: 5` on *Bank B* node
4. Check loan state amount decreased to `5` on both bank nodes
5. Fully settle loan by running `flow start SettleLoanFlow loanId: <OBTAINED_LOAN_ID>, amountToSettle: 5` again on *Bank B* node
6. Check loan state was consumed on both bank nodes