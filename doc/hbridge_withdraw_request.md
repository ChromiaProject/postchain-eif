# WithdrawRequest

Consider a situation where user wants to withdraw tokens from Chromia to EVM.
On the Chromia side he calls an operation `bridge_ft4_token_to_evm_contract` which 
emits a withdrawal event.

The event itself is described by the following structure:

```solidity
struct Event {
    uint256 serialNumber;
    uint256 networkId;
    IERC20 token;
    address beneficiary;
    uint256 amount;
}
```

Solidity abi encoding of this structure is a concatenation of 5 32-byte fields.

When the event is emitted by Rell code on the Chromia side, it is added to the EIF Event Merkle tree.
EIF Event Merkle tree is specifically designed for efficient processing on the EVM side, thus
it uses keccak256 hash function and simple encoding. The leaf of the tree is a hash of the event structure.

The root hash of the event Merkle tree is added to the block header of the Chromia block 
in the EIF extra data field, together with EIF State Merkle tree root hash. (Which is used for mass exit process.)

Chromia block headers are encoded in Chromia-specific data format called GTV. GTV also defines
a standard way to hash data in a merkelized way, where data is transformed into a tree.
We call this way of hashing GTV-hash.

A hash of Chromia block, called blockRID is a GTV-hash of a block header. blockRID is signed by
the validators (aka block producers, signers).

Now on the TokenBridge side, to establish the fact that a withdrawal was signed by the validators,
and thus can be considered valid, we need to verify the following:

1. blockRID is signed by validators
2. block header is GTV-hashed to blockRID
3. event root hash is present in block header extra data
4. event Merkle tree contains hash of the event
5. event hash is a hash of event data

Let's consider parameters passed to withdrawRequest function in the TokenBridge contract:

```solidity
bytes memory _event,
Data.Proof memory eventProof,
bytes memory blockHeader,
bytes[] memory sigs,
address[] memory signers,
Data.ExtraProofData memory extraProof
```

1. `bytes _event` event data (see `Event` structure above)
2. eventProof - proof that event hash is in the event Merkle tree
3. blockHeader - parts of Chromia block header sufficient to compute blockRID
4. sigs - signatures of validators on blockRID
5. signers - addresses of validators
6. extraProof - Merkle proof that event root hash is in the block header extra data

`eventProof` and `extraProof` are both Merkle proofs, but they use different algorithms:

1. `eventProof` uses keccak256 hash function and simple encoding
2. `extraProof` is based on GTV hashing, which defines use of type tags and use of SHA256 hash function

`extraProof` is more complex because it is part of Chromia block header data structure which is not
designed specifically for EVM processing.

Now let's consider function `_withdrawRequest`:

```solidity

// the contract must be initialized by calling setBlockchainRid to be able to process withdrawals
require(blockchainRid != bytes32(0), "TokenBridge: blockchain rid is not set");

// check if event with this hash was already used
require(_events[eventProof.leaf] == false, "TokenBridge: event hash was already used");

// check extraProof for internal consistency: leaf hash must match the hashed leaf (we could just compute hashedLeaf, but, oh, well...)
// the leaf of the extraProof concatenation of eventRootHash and stateRootHash, 64 bytes in total
require(Hash.hashGtvBytes64Leaf(extraProof.leaf) == extraProof.hashedLeaf, "Postchain: invalid EIF extra data");
        
// hash the block header and verify that extra data is correct and connected to the header        
(uint height, bytes32 blockRid) = Postchain.verifyBlockHeader(blockchainRid, blockHeader, extraProof);

// extract eventRoot
bytes32 eventRoot = MerkleProof.bytesToBytes32(extraProof.leaf, 0);

// withdrawals after mass exit block are not considered valid
if (isMassExit) {
    require(height <= massExitBlock.height, "TokenBridge: cannot withdraw request after the mass exit block height");
}

// check that block signatures are valid
if (!validator.isValidSignatures(blockRid, sigs, signers)) revert("TokenBridge: block signature is invalid");

// check that event hash is in the event Merkle tree
if (!MerkleProof.verify(eventProof.merkleProofs, eventProof.leaf, eventProof.position, eventRoot)) revert("TokenBridge: invalid merkle proof");

return (height, blockRid);
```

After a call to `_withdrawRequest` we have verified correctness of a block header
and have an event hash which is connected to said block header.

Function `_updateWithdraw` then verifies that the event data hashes to the event hash by calling
`Postchain.verifyEvent`:

```solidity
function _updateWithdraw(bytes32 hash, bytes memory _event, uint height, bytes32 blockRid) 
    internal returns (bool) {
Withdraw storage wd = _withdraw[hash];
{
   (IERC20 token, address beneficiary, uint256 amount, uint256 netId) = hash.verifyEvent(_event);
```

Now let's consider details of `Postchain.verifyBlockHeader` function:

```solidity
function verifyBlockHeader(
        bytes32 blockchainRid,
        bytes memory blockHeader,
        Data.ExtraProofData memory proof
    ) internal pure returns (uint, bytes32) {
        // decodeBlockHeader deserializes BlockHeaderData structure and computes blockRID as GTV-hash
        BlockHeaderData memory header = decodeBlockHeader(blockHeader);
        if (blockchainRid != header.blockchainRid) revert("Postchain: invalid blockchain rid");
    
        // check that extra data proof is connected to block header
        require(proof.extraRoot == header.extraDataHashedLeaf, "Postchain: invalid extra data root");
    
        // verify extra data proof using G
        if (!proof.extraMerkleProofs.verifySHA256(proof.hashedLeaf, proof.position, proof.extraRoot)) {
            revert("Postchain: invalid extra merkle proof");
        }
        return (header.height, header.blockRid);
    }
```

`decodeBlockHeader` function decodes BlockHeaderData structure and computes GTV-hash. For efficiency reasons
it is unrolled to perform hashing on the tree of a fixed size.

Note that BlockHeaderData is not the way Chromia block header is normally encoded, but a structure with 
relevant parts of Merkle tree sufficient to compute blockRID.

## Known inefficiencies

It is not necessary to send `hashedLeaf` and `blockRID` in data structures as these values are computed.
The only reason they are sent is ease of development.

## How is the proof data obtained?

Chromia node has an endpoint which returns all data necessary for withdrawRequest call.

Specifically, `get_event_merkle_proof` query, which can be called by postchain-client, a library
which lets clients to communicate with Chromia node.

Query is implemented in `net.postchain.eif.EifGTXModule`, more specifically function `eventMerkleProofQuery`,
see here: https://gitlab.com/chromaway/core/postchain-eif/-/blob/QuantStamp-Audit/postchain-eif-core/src/main/kotlin/net/postchain/eif/EifGTXModule.kt?ref_type=heads#L108

Example data can be found in postchain-eif-contracts unit tests.
`postchain-eif-core` has unit test & integration tests which computes this data.
