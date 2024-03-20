// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.20;

// Interfaces
import "@openzeppelin/contracts/token/ERC20/IERC20.sol";

// Internal libraries
import "./utils/cryptography/Hash.sol";
import "./utils/cryptography/MerkleProof.sol";
import "./Data.sol";

interface ChromiaToken is IERC20 {
    function transferFromChromia(address to, uint256 value, bytes32 refID) external returns (bool);

    function changeMinter(address newMinter) external;
}

library Postchain {
    using MerkleProof for bytes32[];

    struct Event {
        uint256 serialNumber;
        uint256 networkId;
        ChromiaToken token;
        address beneficiary;
        uint256 amount;
    }

    struct BlockHeaderData {
        bytes32 blockchainRid;
        bytes32 blockRid;
        bytes32 previousBlockRid;
        bytes32 merkleRootHashHashedLeaf;
        uint timestamp;
        uint height;
        bytes32 dependenciesHashedLeaf;
        bytes32 extraDataHashedLeaf;
    }

    function verifyEvent(
        bytes32 _hash,
        bytes memory _event
    ) internal pure returns (ChromiaToken, address, uint256, uint256) {
        Event memory evt = abi.decode(_event, (Event));
        bytes32 hash = keccak256(_event);
        if (hash != _hash) {
            revert("Postchain: invalid event");
        }
        return (evt.token, evt.beneficiary, evt.amount, evt.networkId);
    }

    function verifyBlockHeader(
        bytes32 blockchainRid,
        bytes memory blockHeader,
        Data.ExtraProofData memory proof
    ) internal pure returns (uint, bytes32, bytes32, bytes32) {
        require(Hash.hashGtvBytes64Leaf(proof.leaf) == proof.hashedLeaf, "Postchain: invalid EIF extra data");
        BlockHeaderData memory header = _decodeBlockHeader(blockHeader);
        if (blockchainRid != header.blockchainRid) revert("Postchain: invalid blockchain rid");
        require(proof.extraRoot == header.extraDataHashedLeaf, "Postchain: invalid extra data root");
        if (!proof.extraMerkleProofs.verifySHA256(proof.hashedLeaf, proof.position, proof.extraRoot)) {
            revert("Postchain: invalid EIF extra merkle proof");
        }
        return (header.height, header.blockRid, _bytesToBytes32(proof.leaf, 0), _bytesToBytes32(proof.leaf, 32));
    }

    function _decodeBlockHeader(bytes memory blockHeader) internal pure returns (BlockHeaderData memory) {
        BlockHeaderData memory header = abi.decode(blockHeader, (BlockHeaderData));

        bytes32 node12 = sha256(
            abi.encodePacked(
                uint8(0x00),
                Hash.hashGtvBytes32Leaf(header.blockchainRid),
                Hash.hashGtvBytes32Leaf(header.previousBlockRid)
            )
        );

        bytes32 node34 = sha256(
            abi.encodePacked(uint8(0x00), header.merkleRootHashHashedLeaf, Hash.hashGtvIntegerLeaf(header.timestamp))
        );

        bytes32 node56 = sha256(
            abi.encodePacked(uint8(0x00), Hash.hashGtvIntegerLeaf(header.height), header.dependenciesHashedLeaf)
        );

        bytes32 node1234 = sha256(abi.encodePacked(uint8(0x00), node12, node34));

        bytes32 node5678 = sha256(abi.encodePacked(uint8(0x00), node56, header.extraDataHashedLeaf));

        bytes32 blockRid = sha256(
            abi.encodePacked(
                uint8(0x7), // Gtv merkle tree Array Root Node prefix
                node1234,
                node5678
            )
        );

        if (blockRid != header.blockRid) revert("Postchain: invalid block header");
        return header;
    }

    function _bytesToBytes32(bytes memory b, uint offset) internal pure returns (bytes32) {
        bytes32 out;

        for (uint i = 0; i < 32; i++) {
            out |= bytes32(b[offset + i] & 0xFF) >> (i * 8);
        }
        return out;
    }
}
