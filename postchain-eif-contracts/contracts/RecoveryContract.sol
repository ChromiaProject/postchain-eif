// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.24;

// Upgradeable implementations
import "@openzeppelin/contracts-upgradeable/proxy/utils/Initializable.sol";
import "@openzeppelin/contracts-upgradeable/access/Ownable2StepUpgradeable.sol";
import "@openzeppelin/contracts-upgradeable/utils/PausableUpgradeable.sol";
import "@openzeppelin/contracts-upgradeable/utils/ReentrancyGuardUpgradeable.sol";

import "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";

// Internal libraries
import "./Postchain.sol";
import "./IValidator.sol";
import "./validatorupdate/IManagedValidator.sol";

contract RecoveryContract is Initializable, PausableUpgradeable, Ownable2StepUpgradeable, ReentrancyGuardUpgradeable {

    using Postchain for bytes32;

    uint constant EMERGENCY_DURATION = 90 days;
    uint256 public emergencyTimestamp;

    using SafeERC20 for IERC20;

    uint8 constant ERC20_BALANCE_RECORD_BYTE_SIZE = 64;
    uint8 constant ERC20_STATE_HEADER_BYTE_SIZE = 32 + 32 + 32;
    // @dev Hexadecimal representation of the "hbridge:erc20:v1" string followed by 16x 0x01 bytes, 32 bytes in total.
    bytes32 constant ERC20_STATE_TAG_V1 = 0x686272696467653a65726332303a763101010101010101010101010101010101;

    uint256 public networkId;
    IValidator public validator;
    bytes32 internal blockchainRid;         // @dev Postchain/Chromia blockchain RID
    bool public isBlockchainRidFinalized;   // @dev Flag to track if blockchain RID is finalized
    mapping(IERC20 => bool) public _allowedToken;
    bool public isMassExit;
    PostchainBlock public massExitBlock;
    bytes32 public massExitStateRoot;
    // @dev each account state snapshot will be used to claim only one time.
    mapping(bytes32 => bool) internal _snapshots;

    // Structs
    struct PostchainBlock {
        uint height;
        bytes32 blockRid;
        bytes32 extraDataHashedLeaf;
    }

    struct ERC20StateHeader {
        bytes32 tag;
        address beneficiary;
        uint256 discriminator;
    }

    struct ERC20BalanceRecord {
        IERC20 token;
        uint amount;
    }

    // Events
    event Initialize(IValidator indexed _validator);
    event SetBlockchainRid(bytes32 rid);
    event BlockchainRidFinalized(bytes32 rid);
    event AllowToken(IERC20 indexed token);
    event TriggerMassExit(uint indexed height, bytes32 indexed blockRid);
    event WithdrawalBySnapshot(address indexed beneficiary);

    // Modifiers
    modifier whenMassExit() {
        require(isMassExit, "TokenBridge: mass exit was not triggered yet");
        _;
    }

    modifier whenNotMassExit() {
        require(!isMassExit, "TokenBridge: action is not allowed during mass exit");
        _;
    }

    modifier onlyValidator() {
        require(validator.isValidator(msg.sender), "TokenBridge: sender is not a validator.");
        _;
    }

    function initialize(IValidator _validator) public initializer {
        require(address(_validator) != address(0), "TokenBridge: validator address is invalid");
        __Ownable_init(msg.sender);
        __Pausable_init();
        __ReentrancyGuard_init();

        uint256 id;
        assembly {
            id := chainid()
        }
        networkId = id;
        validator = _validator;
        isBlockchainRidFinalized = false;
        emit Initialize(_validator);
    }

    function renounceOwnership() public override view onlyOwner {
        revert("TokenBridge: renounce ownership is not allowed");
    }

    function setBlockchainRid(bytes32 rid) public onlyOwner {
        require(!isBlockchainRidFinalized, "TokenBridge: blockchain rid has been finalized");
        require(rid != bytes32(0), "TokenBridge: blockchain rid is invalid");
        blockchainRid = rid;
        emit SetBlockchainRid(rid);
    }

    // Function to finalize blockchain RID
    function finalizeBlockchainRid() public onlyOwner {
        require(!isBlockchainRidFinalized, "TokenBridge: blockchain rid has been already finalized");
        require(blockchainRid != bytes32(0), "TokenBridge: blockchain rid is not set");
        isBlockchainRidFinalized = true;
        emit BlockchainRidFinalized(blockchainRid);
    }

    function pause() onlyValidator public {
        _pause();
    }

    function unpause() public onlyOwner {
        _unpause();
    }

    function allowToken(IERC20 token) public onlyOwner {
        require(address(token) != address(0), "TokenBridge: token address is invalid");
        _allowedToken[token] = true;
        emit AllowToken(token);
    }

    // Check that state header has correct discriminator and tag
    function requireERC20StateHeader(ERC20StateHeader memory header, bytes32 expectedTag) internal view {
        require(header.tag == expectedTag, "TokenBridge: invalid snapshot tag");
        Postchain.verifyDiscriminator(networkId, header.discriminator);
    }

    /**
     * @dev withdraw all account assets in the postchain snapshot when mass exit was triggered
     * Note: the mass exit block should be the block at which snapshot was updated
     * with state root was stored properly in the block header extra data.
     */
    function withdrawBySnapshot(
        bytes calldata snapshot,
        Data.Proof memory stateProof
    ) public virtual whenMassExit whenNotPaused nonReentrant {
        require(_snapshots[stateProof.leaf] == false, "TokenBridge: snapshot already used");
        require(stateProof.leaf == keccak256(snapshot), "TokenBridge: snapshot data is not correct");
        bytes32 stateRoot = massExitStateRoot;
        if (!MerkleProof.verify(stateProof.merkleProofs, stateProof.leaf, stateProof.position, stateRoot))
            revert("TokenBridge: invalid merkle proof");

        ERC20StateHeader memory header = abi.decode(snapshot[: ERC20_STATE_HEADER_BYTE_SIZE], (ERC20StateHeader));
        requireERC20StateHeader(header, ERC20_STATE_TAG_V1);

        address beneficiary = header.beneficiary;
        uint offset = ERC20_STATE_HEADER_BYTE_SIZE;
        uint endOffset = snapshot.length;

        _snapshots[stateProof.leaf] = true;

        for (uint i = offset; i < endOffset; i += ERC20_BALANCE_RECORD_BYTE_SIZE) {
            ERC20BalanceRecord memory balanceRecord = abi.decode(
                snapshot[i : i + ERC20_BALANCE_RECORD_BYTE_SIZE],
                (ERC20BalanceRecord)
            );
            if (balanceRecord.amount > 0 && _allowedToken[balanceRecord.token]) {
//                transferWithdraw(balanceRecord.token, beneficiary, balanceRecord.amount);
                balanceRecord.token.safeTransfer(beneficiary, balanceRecord.amount);
            }
        }

        emit WithdrawalBySnapshot(beneficiary);
    }

    function triggerMassExitWithHistoricalValidators(
        bytes memory blockHeader,
        bytes[] memory sigs,
        address[] memory signers,
        Data.ExtraProofData memory extraProof,
        address[] memory historicalValidators
    ) public onlyOwner {
        Postchain.BlockHeaderData memory header = Postchain.decodeBlockHeader(blockHeader);
        // Unsafe cast but this function is pointless for manually updated validator contracts
        IManagedValidator managedValidator = IManagedValidator(address(validator));
        require(managedValidator.isValidSignaturesWithHistoricalValidators(header.blockRid, sigs, signers, historicalValidators, block.timestamp - 3 days), "TokenBridge: block signature is invalid");
        validateMassExit(header, extraProof);
    }

    function triggerMassExit(
        bytes memory blockHeader,
        bytes[] memory sigs,
        address[] memory signers,
        Data.ExtraProofData memory extraProof
    ) public onlyOwner {
        Postchain.BlockHeaderData memory header = Postchain.decodeBlockHeader(blockHeader);
        require(validator.isValidSignatures(header.blockRid, sigs, signers), "TokenBridge: block signature is invalid");
        validateMassExit(header, extraProof);
    }

    function validateMassExit(Postchain.BlockHeaderData memory header, Data.ExtraProofData memory extraProof) internal {
        require(blockchainRid != bytes32(0), "TokenBridge: blockchain rid is not set");
        require(header.timestamp >= (block.timestamp - 3 days) * 1000, "TokenBridge: mass exit block is too old");
        require(blockchainRid == header.blockchainRid, "TokenBridge: invalid blockchain rid");

        require(extraProof.extraRoot == header.extraDataHashedLeaf, "Postchain: invalid extra data root");

        // Check extraProof against the mass exit block and for internal consistency
        require(Hash.hashGtvBytes64Leaf(extraProof.leaf) == extraProof.hashedLeaf, "Postchain: invalid EIF extra data");
        if (!MerkleProof.verifySHA256(extraProof.extraMerkleProofs, extraProof.hashedLeaf, extraProof.position, extraProof.extraRoot)) {
            revert("Postchain: invalid extra merkle proof");
        }
        if (extraProof.extraMerkleProofs[0] != Postchain.EIF_KEY_MERKLE_HASH) {
            revert("Postchain: proof does not originate from EIF");
        }

        massExitStateRoot = MerkleProof.bytesToBytes32(extraProof.leaf, 32);

        isMassExit = true;
        if (paused()) {
            _unpause();
        }
        massExitBlock = PostchainBlock(header.height, header.blockRid, header.extraDataHashedLeaf);
        emergencyTimestamp = block.timestamp + EMERGENCY_DURATION;
        emit TriggerMassExit(header.height, header.blockRid);
    }

    /**
     * @notice this function will be use only in emergency case
     * by allow admin/owner (multi-sig wallet) to withdraw all the remaining balance after a specific period of time
     * has passed since mass exit.
     */
    function emergencyWithdraw(IERC20 token, address payable beneficiary) external onlyOwner whenMassExit {
        require(address(token) != address(0), "TokenBridge: token address is invalid");
        require(beneficiary != address(0), "TokenBridge: beneficiary address is invalid");
        require(block.timestamp >= emergencyTimestamp, "TokenBridge: cannot do emergency withdrawal until 90 days after mass exit");
        uint tokenBalance = token.balanceOf(address(this));
        if (tokenBalance > 0) {
            token.safeTransfer(beneficiary, tokenBalance);
        }
    }
}
