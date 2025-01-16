// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.24;

// Interfaces
import "@openzeppelin/contracts/token/ERC20/IERC20.sol";

// Internal libraries
import "./utils/cryptography/Hash.sol";
import "./utils/cryptography/MerkleProof.sol";
import "./Data.sol";

library Postchain {

    // Merkle hash of GTV String "eif"
    bytes32 constant EIF_KEY_MERKLE_HASH = 0x1E816A557ACB74AEBECC8B0598B81DFCDBCA912CA8BA030740F5BEAEF3FF0797;

    using MerkleProof for bytes32[];

    struct Event {
        uint256 serialNumber;
        uint256 discriminator;
        IERC20 token;
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

    function verifyEvent(bytes32 _hash, bytes memory _event) internal pure returns (IERC20, address, uint256, uint256) {
        Event memory evt = abi.decode(_event, (Event));
        bytes32 hash = keccak256(_event);
        if (hash != _hash) {
            revert("Postchain: invalid event");
        }
        return (evt.token, evt.beneficiary, evt.amount, evt.discriminator);
    }

    function verifyBlockHeader(
        bytes32 blockchainRid,
        bytes memory blockHeader,
        Data.ExtraProofData memory proof,
        bytes32 extraDataKey
    ) internal pure returns (uint, bytes32) {
        BlockHeaderData memory header = decodeBlockHeader(blockHeader);
        if (blockchainRid != header.blockchainRid) revert("Postchain: invalid blockchain rid");
        require(proof.extraRoot == header.extraDataHashedLeaf, "Postchain: invalid extra data root");
        if (!proof.extraMerkleProofs.verifySHA256(proof.hashedLeaf, proof.position, proof.extraRoot)) {
            revert("Postchain: invalid extra merkle proof");
        }
        if (proof.extraMerkleProofs[0] != extraDataKey) {
            revert("Postchain: proof does not originate from EIF");
        }
        return (header.height, header.blockRid);
    }

    function decodeBlockHeader(
        bytes memory blockHeader
    ) internal pure returns (BlockHeaderData memory) {
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

    function verifyDiscriminator(uint256 networkId, uint256 discriminator) internal view {
        uint256 networkDiscriminator = networkId << 160;
        uint256 networkContractDiscriminator = networkDiscriminator + uint160(address(this));

        bool isValid = (discriminator == networkDiscriminator)
            || (discriminator == networkContractDiscriminator);

        require(isValid, "Invalid discriminator. Please verify the network ID and bridge contract.");
    }

}
