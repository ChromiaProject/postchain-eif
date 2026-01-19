// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.24;

// Upgradeable implementations
import "@openzeppelin/contracts-upgradeable/proxy/utils/Initializable.sol";
import "@openzeppelin/contracts-upgradeable/access/Ownable2StepUpgradeable.sol";
import "@openzeppelin/contracts-upgradeable/utils/PausableUpgradeable.sol";
import "@openzeppelin/contracts-upgradeable/utils/ReentrancyGuardUpgradeable.sol";

import "@openzeppelin/contracts/token/ERC721/ERC721.sol";
import "@openzeppelin/contracts/token/ERC1155/ERC1155.sol";
import "@openzeppelin/contracts/token/ERC721/IERC721.sol";
import "@openzeppelin/contracts/token/ERC1155/IERC1155.sol";
import "@openzeppelin/contracts/token/ERC721/IERC721Receiver.sol";
import "@openzeppelin/contracts/token/ERC1155/IERC1155Receiver.sol";
import "@openzeppelin/contracts/utils/introspection/ERC165.sol";

// Internal libraries
import "../Postchain.sol";
import "../IValidator.sol";

// This contract is upgradeable. This imposes restrictions on how storage layout can be modified once it is deployed
// Some instructions are also not allowed. Read more at: https://docs.openzeppelin.com/upgrades-plugins/1.x/writing-upgradeable
// Note: To enhance the security & decentralization, we should call transferOwnership() to external multi-sig owner after deploy the smart contract
contract NFTBridge is Initializable, PausableUpgradeable, Ownable2StepUpgradeable, ReentrancyGuardUpgradeable, IERC721Receiver, IERC1155Receiver {

    using Postchain for bytes32;
    using MerkleProof for bytes32[];

    struct AllowedContract {
        uint256 protocolId;
        bool isAllowed;
    }
        
    struct Withdrawal {
        uint256 protocolId;
        address contractAddress;
        uint256[] tokenIds;
        uint256[] amounts;
        address beneficiary;
        uint256 blockNumber;
        uint256 postchainHeight;
        Status status;
    }

    struct TokenEvent {
        uint256 serialNumber;
        uint256 discriminator;
        uint256 protocolId;
        address contractAddress;
        uint256[] tokenIds;
        uint256[] amounts;
        address beneficiary;
    }

    mapping(address => AllowedContract) public allowedContracts;
    
    // Withdrawal data by hash
    mapping(bytes32 => Withdrawal) public withdrawals;

    IValidator public validator;
    uint256 public networkId;
    bool public isMassExit;
    Postchain.PostchainBlock public massExitBlock;
    uint256 public withdrawOffset;
    bool public requireAllowedContracts;

    bytes32 internal blockchainRid;         // @dev Postchain/Chromia blockchain RID
    bool public isBlockchainRidFinalized;   // @dev Flag to track if blockchain RID is finalized

    // Each postchain event will be used to claim only one time.
    mapping(bytes32 => bool) internal _events;

    enum Status {
        Uninitialized, // to prevent creating empty Withdraw objects by unused hash in unpendingWithdraw()
        Pending,
        Withdrawable,
        Withdrawn,
        PostchainWithdrawn
    }

    event Initialize(IValidator indexed _validator, uint256 _withdrawOffset);
    event SetBlockchainRid(bytes32 rid);
    event BlockchainRidFinalized(bytes32 rid);
    event AllowContract(address indexed contractAddress, uint256 protocolId);
    event TriggerMassExit(uint indexed height, bytes32 indexed blockRid);
    event PendingWithdraw(bytes32 indexed hash);
    event UnpendingWithdraw(bytes32 indexed hash);
    event DepositedTokens(address indexed sender, address indexed contractAddress, uint256[] tokenIds, uint256[] amounts, bytes32 accountID, uint256 protocolId);
    event TokenWithdrawRequest(uint256 protocolId, address indexed contractAddress, uint256[] tokenIds, uint256[] amounts, address indexed beneficiary, uint height, bytes32 blockRid);
    event WithdrawRequestHash(bytes32 indexed hash);
    event TokenWithdrawal(uint256 protocolId, address indexed contractAddress, uint256[] tokenIds, uint256[] amounts, address indexed beneficiary);
    event WithdrawalHash(bytes32 indexed hash);
    event WithdrawalToPostchain(bytes32 indexed hash);
    event LinkAccountID(address indexed sender, bytes32 accountID, bool isContract);

    modifier isAllowedContract(address contractAddress) {
        if (requireAllowedContracts) {
            AllowedContract memory allowedContract = allowedContracts[contractAddress];
            require(allowedContract.isAllowed, "NFTBridge: token not allowed");
        }
        _;
    }

    modifier whenMassExit() {
        require(isMassExit, "NFTBridge: mass exit was not triggered yet");
        _;
    }

    modifier whenNotMassExit() {
        require(!isMassExit, "NFTBridge: action is not allowed during mass exit");
        _;
    }

    modifier onlyValidator() {
        require(validator.isValidator(msg.sender), "NFTBridge: sender is not a validator.");
        _;
    }

    function initialize(IValidator _validator, uint256 _withdrawOffset) public initializer {
        require(address(_validator) != address(0), "NFTBridge: validator address is invalid");
        __Ownable_init(msg.sender);
        __Pausable_init();
        __ReentrancyGuard_init();

        uint256 id;
        assembly {
            id := chainid()
        }
        networkId = id;
        validator = _validator;
        withdrawOffset = _withdrawOffset;
        isBlockchainRidFinalized = false;
        requireAllowedContracts = true;
        emit Initialize(_validator, _withdrawOffset);
    }

    function renounceOwnership() public override view onlyOwner {
        revert("NFTBridge: renounce ownership is not allowed");
    }

    function setBlockchainRid(bytes32 rid) public onlyOwner {
        require(!isBlockchainRidFinalized, "NFTBridge: blockchain rid has been finalized");
        require(rid != bytes32(0), "NFTBridge: blockchain rid is invalid");
        blockchainRid = rid;
        emit SetBlockchainRid(rid);
    }

    // Function to finalize blockchain RID
    function finalizeBlockchainRid() public onlyOwner {
        require(!isBlockchainRidFinalized, "NFTBridge: blockchain rid has been already finalized");
        require(blockchainRid != bytes32(0), "NFTBridge: blockchain rid is not set");
        isBlockchainRidFinalized = true;
        emit BlockchainRidFinalized(blockchainRid);
    }

    function pause() onlyValidator public {
        _pause();
    }

    function unpause() public onlyOwner {
        _unpause();
    }

    function allowContract(address contractAddress, uint256 protocolId) public onlyOwner {
        require(contractAddress != address(0), "NFTBridge: contract address is invalid");
        require(protocolId == 721 || protocolId == 1155, "NFTBridge: invalid protocol ID");
        
        allowedContracts[contractAddress] = AllowedContract({
            protocolId: protocolId,
            isAllowed: true
        });
        
        emit AllowContract(contractAddress, protocolId);
    }

    function pendingWithdraw(bytes32 _hash) onlyOwner public {
        require(_hash != bytes32(0), "NFTBridge: event hash is invalid");
        Withdrawal storage wd = withdrawals[_hash];
        require(wd.status == Status.Withdrawable, "NFTBridge: withdraw request status is not withdrawable");
        wd.status = Status.Pending;
        emit PendingWithdraw(_hash);
    }

    function unpendingWithdraw(bytes32 _hash) public onlyOwner {
        require(_hash != bytes32(0), "NFTBridge: event hash is invalid");
        Withdrawal storage wd = withdrawals[_hash];
        require(wd.status == Status.Pending, "NFTBridge: withdraw request status is not pending");
        wd.status = Status.Withdrawable;
        emit UnpendingWithdraw(_hash);
    }

    function setRequireAllowedContracts(bool _requireAllowedContracts) public onlyOwner {
        requireAllowedContracts = _requireAllowedContracts;
    }

    function isContract(address addr) internal view returns (bool) {
        // Note: We are aware of the fact that this might return false even when the address is a contract.
        // It is fine for our purposes. We want to prevent EOA from calling depositToAccountID.
        return addr.code.length > 0;
    }

    modifier onlyContract() {
        require(isContract(msg.sender), "NFTBridge: only contract can call this function");
        _;
    }

    modifier onlyEOA() {
        require(!isContract(msg.sender), "NFTBridge: only EOA can call this function");
        _;
    }

    /**
     * @dev Deposit ERC721 tokens from an EOA. The recipient account on Chromia is derived from the sender's address.
     * Smart contracts should use depositERC721ToAccountID() to specify an explicit recipient.
     */
    function depositERC721(address contractAddress, uint256[] calldata tokenIds) public
        isAllowedContract(contractAddress)
        whenNotPaused
        whenNotMassExit
        onlyEOA
        returns (bool)
    {
        for (uint256 i = 0; i < tokenIds.length; i++) {
            IERC721(contractAddress).safeTransferFrom(msg.sender, address(this), tokenIds[i]);
        }
        emit DepositedTokens(msg.sender, contractAddress, tokenIds, new uint256[](0), bytes32(0), 721); // accountID will be determined from sender
        return true;
    }

    /**
     * @dev Deposit ERC1155 tokens from an EOA. The recipient account on Chromia is derived from the sender's address.
     * Smart contracts should use depositERC1155ToAccountID() to specify an explicit recipient.
     */
    function depositERC1155(address contractAddress, uint256[] calldata tokenIds, uint256[] calldata amounts) public
        isAllowedContract(contractAddress)
        whenNotPaused
        whenNotMassExit
        onlyEOA
        returns (bool)
    {        
        IERC1155(contractAddress).safeBatchTransferFrom(msg.sender, address(this), tokenIds, amounts, "");
        emit DepositedTokens(msg.sender, contractAddress, tokenIds, amounts, bytes32(0), 1155); // accountID will be determined from sender
        return true;
    }

    /**
     * @dev Deposit ERC721 tokens to a specific account ID. Restricted to smart contracts only.
     * EOA users must use depositERC721() where the recipient is derived from their address.
     * This prevents users from accidentally specifying wrong account IDs.
     * The calling contract is responsible for correctly managing recipient account IDs.
     */
    function depositERC721ToAccountID(address contractAddress, uint256[] calldata tokenIds, bytes32 accountID) public
        isAllowedContract(contractAddress)
        whenNotPaused
        whenNotMassExit
        onlyContract
        returns (bool)
    {
        require(accountID != bytes32(0), "NFTBridge: invalid accountID, cannot be zero.");
        
        for (uint256 i = 0; i < tokenIds.length; i++) {
            IERC721(contractAddress).safeTransferFrom(msg.sender, address(this), tokenIds[i]);
        }
        emit DepositedTokens(msg.sender, contractAddress, tokenIds, new uint256[](0), accountID, 721);
        return true;
    }

    /**
     * @dev Deposit ERC1155 tokens to a specific account ID. Restricted to smart contracts only.
     * EOA users must use depositERC1155() where the recipient is derived from their address.
     * This prevents users from accidentally specifying wrong account IDs.
     * The calling contract is responsible for correctly managing recipient account IDs.
     */
    function depositERC1155ToAccountID(address contractAddress, uint256[] calldata tokenIds, uint256[] calldata amounts, bytes32 accountID) public
        isAllowedContract(contractAddress)
        whenNotPaused
        whenNotMassExit
        onlyContract
        returns (bool)
    {
        require(accountID != bytes32(0), "NFTBridge: invalid accountID, cannot be zero.");
        
        IERC1155(contractAddress).safeBatchTransferFrom(msg.sender, address(this), tokenIds, amounts, "");
        emit DepositedTokens(msg.sender, contractAddress, tokenIds, amounts, accountID, 1155);
        return true;
    }

    function linkAccountID(bytes32 accountID) external {
        emit LinkAccountID(msg.sender, accountID, isContract(msg.sender));
    }

    /**
     * @dev signers should be order ascending
     */
    function requestWithdrawal(
        bytes memory _event,
        Data.Proof memory eventProof,
        bytes memory blockHeader,
        bytes[] memory sigs,
        address[] memory signers,
        Data.ExtraProofData memory extraProof
    ) external whenNotMassExit whenNotPaused nonReentrant {
        (uint height, bytes32 blockRid) = _verifyWithdrawRequest(eventProof, blockHeader, sigs, signers, extraProof);
        _events[eventProof.leaf] = _updateWithdrawal(eventProof.leaf, _event, height, blockRid); // mark the event hash was already used.
    }

    function _verifyWithdrawRequest(
        Data.Proof memory eventProof,
        bytes memory blockHeader,
        bytes[] memory sigs,
        address[] memory signers,
        Data.ExtraProofData memory extraProof
    ) internal view returns (uint, bytes32) {
        require(blockchainRid != bytes32(0), "NFTBridge: blockchain rid is not set");
        require(_events[eventProof.leaf] == false, "NFTBridge: event hash was already used");

        require(Hash.hashGtvBytes64Leaf(extraProof.leaf) == extraProof.hashedLeaf, "Postchain: invalid EIF extra data");
        (uint height, bytes32 blockRid) = Postchain.verifyBlockHeader(blockchainRid, blockHeader, extraProof, Postchain.EIF_KEY_MERKLE_HASH);
        bytes32 eventRoot = MerkleProof.bytesToBytes32(extraProof.leaf, 0);
        
        if (!validator.isValidSignatures(blockRid, sigs, signers)) {
            revert("NFTBridge: block signature is invalid");
        }
        
        if (!MerkleProof.verify(eventProof.merkleProofs, eventProof.leaf, eventProof.position, eventRoot)) {
            revert("NFTBridge: invalid merkle proof");
        }

        return (height, blockRid);
    }

    function _updateWithdrawal(bytes32 hash, bytes memory _event, uint height, bytes32 blockRid) internal returns (bool) {
        Withdrawal storage wd = withdrawals[hash];
        {
            TokenEvent memory evt = abi.decode(_event, (TokenEvent));
            require(keccak256(_event) == hash, "NFTBridge: invalid event");
            Postchain.verifyDiscriminator(networkId, address(this), evt.discriminator);
            
            AllowedContract memory allowedContract = allowedContracts[evt.contractAddress];
            require(allowedContract.isAllowed, "NFTBridge: token not allowed");
            require(allowedContract.protocolId == evt.protocolId, "NFTBridge: protocol ID mismatch");
            require(evt.tokenIds.length == evt.amounts.length, "NFTBridge: tokenIds and amounts length mismatch");
            wd.protocolId = evt.protocolId;
            wd.contractAddress = evt.contractAddress;
            wd.tokenIds = evt.tokenIds;
            wd.amounts = evt.amounts;
            wd.beneficiary = evt.beneficiary;
            wd.postchainHeight = height;
            wd.blockNumber = block.number + withdrawOffset;
            wd.status = Status.Withdrawable;
            
            emit TokenWithdrawRequest(evt.protocolId, evt.contractAddress, evt.tokenIds, evt.amounts, evt.beneficiary, height, blockRid);
            emit WithdrawRequestHash(hash);
        }
        return true;
    }

    function withdraw(bytes32 _hash, address beneficiary) external whenNotMassExit whenNotPaused nonReentrant {
        Withdrawal storage wd = withdrawals[_hash];
        require(wd.status != Status.Uninitialized, "NFTBridge: withdraw request not found");
        require(wd.beneficiary == beneficiary, "NFTBridge: no fund for the beneficiary");
        require(wd.blockNumber <= block.number, "NFTBridge: not mature enough to withdraw the fund");
        require(wd.status == Status.Withdrawable, "NFTBridge: fund is pending or was already claimed");
        
        wd.status = Status.Withdrawn;
        
        if (wd.protocolId == 721) {
            for (uint256 i = 0; i < wd.tokenIds.length; i++) {
                IERC721(wd.contractAddress).safeTransferFrom(address(this), beneficiary, wd.tokenIds[i]);
            }
        } else if (wd.protocolId == 1155) {
            IERC1155(wd.contractAddress).safeBatchTransferFrom(address(this), beneficiary, wd.tokenIds, wd.amounts, "");
        }
        
        emit TokenWithdrawal(wd.protocolId, wd.contractAddress, wd.tokenIds, wd.amounts, beneficiary);
        emit WithdrawalHash(_hash);
    }

    /**
     * @dev user can withdraw token back to postchain if they cannot withdraw on EVM chain
     */
    function withdrawToPostchain(bytes32 _hash) external whenNotPaused nonReentrant {
        Withdrawal storage wd = withdrawals[_hash];
        require(wd.status != Status.Uninitialized, "NFTBridge: withdraw request not found");
        require(wd.beneficiary == msg.sender, "NFTBridge: no fund for the beneficiary");
        require(wd.blockNumber <= block.number, "NFTBridge: not mature enough to withdraw the fund");
        require(wd.status == Status.Withdrawable, "NFTBridge: fund is pending or was already claimed");
        
        wd.status = Status.PostchainWithdrawn;
        
        emit DepositedTokens(msg.sender, wd.contractAddress, wd.tokenIds, wd.amounts, bytes32(0), wd.protocolId);
        emit WithdrawalToPostchain(_hash);
    }

    /**
     * @dev See {IERC165-supportsInterface}.
     * Supports both ERC721 and ERC1155 receiver interfaces
     */
    function supportsInterface(bytes4 interfaceId) public pure override returns (bool) {
        return
            interfaceId == type(IERC721Receiver).interfaceId ||
            interfaceId == type(IERC1155Receiver).interfaceId;
    }

    function onERC721Received(address, address, uint256, bytes calldata) external pure override returns (bytes4) {
        return IERC721Receiver.onERC721Received.selector;
    }

    function onERC1155Received(address, address, uint256, uint256, bytes calldata) external pure override returns (bytes4) {
        return IERC1155Receiver.onERC1155Received.selector;
    }

    function onERC1155BatchReceived(address, address, uint256[] calldata, uint256[] calldata, bytes calldata) external pure override returns (bytes4) {
        return IERC1155Receiver.onERC1155BatchReceived.selector;
    }
}
