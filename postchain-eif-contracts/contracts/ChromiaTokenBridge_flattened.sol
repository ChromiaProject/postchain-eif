
// File: @openzeppelin/contracts-upgradeable/proxy/utils/Initializable.sol


// OpenZeppelin Contracts (last updated v5.3.0) (proxy/utils/Initializable.sol)

pragma solidity ^0.8.20;

/**
 * @dev This is a base contract to aid in writing upgradeable contracts, or any kind of contract that will be deployed
 * behind a proxy. Since proxied contracts do not make use of a constructor, it's common to move constructor logic to an
 * external initializer function, usually called `initialize`. It then becomes necessary to protect this initializer
 * function so it can only be called once. The {initializer} modifier provided by this contract will have this effect.
 *
 * The initialization functions use a version number. Once a version number is used, it is consumed and cannot be
 * reused. This mechanism prevents re-execution of each "step" but allows the creation of new initialization steps in
 * case an upgrade adds a module that needs to be initialized.
 *
 * For example:
 *
 * [.hljs-theme-light.nopadding]
 * ```solidity
 * contract MyToken is ERC20Upgradeable {
 *     function initialize() initializer public {
 *         __ERC20_init("MyToken", "MTK");
 *     }
 * }
 *
 * contract MyTokenV2 is MyToken, ERC20PermitUpgradeable {
 *     function initializeV2() reinitializer(2) public {
 *         __ERC20Permit_init("MyToken");
 *     }
 * }
 * ```
 *
 * TIP: To avoid leaving the proxy in an uninitialized state, the initializer function should be called as early as
 * possible by providing the encoded function call as the `_data` argument to {ERC1967Proxy-constructor}.
 *
 * CAUTION: When used with inheritance, manual care must be taken to not invoke a parent initializer twice, or to ensure
 * that all initializers are idempotent. This is not verified automatically as constructors are by Solidity.
 *
 * [CAUTION]
 * ====
 * Avoid leaving a contract uninitialized.
 *
 * An uninitialized contract can be taken over by an attacker. This applies to both a proxy and its implementation
 * contract, which may impact the proxy. To prevent the implementation contract from being used, you should invoke
 * the {_disableInitializers} function in the constructor to automatically lock it when it is deployed:
 *
 * [.hljs-theme-light.nopadding]
 * ```
 * /// @custom:oz-upgrades-unsafe-allow constructor
 * constructor() {
 *     _disableInitializers();
 * }
 * ```
 * ====
 */
abstract contract Initializable {
    /**
     * @dev Storage of the initializable contract.
     *
     * It's implemented on a custom ERC-7201 namespace to reduce the risk of storage collisions
     * when using with upgradeable contracts.
     *
     * @custom:storage-location erc7201:openzeppelin.storage.Initializable
     */
    struct InitializableStorage {
        /**
         * @dev Indicates that the contract has been initialized.
         */
        uint64 _initialized;
        /**
         * @dev Indicates that the contract is in the process of being initialized.
         */
        bool _initializing;
    }

    // keccak256(abi.encode(uint256(keccak256("openzeppelin.storage.Initializable")) - 1)) & ~bytes32(uint256(0xff))
    bytes32 private constant INITIALIZABLE_STORAGE = 0xf0c57e16840df040f15088dc2f81fe391c3923bec73e23a9662efc9c229c6a00;

    /**
     * @dev The contract is already initialized.
     */
    error InvalidInitialization();

    /**
     * @dev The contract is not initializing.
     */
    error NotInitializing();

    /**
     * @dev Triggered when the contract has been initialized or reinitialized.
     */
    event Initialized(uint64 version);

    /**
     * @dev A modifier that defines a protected initializer function that can be invoked at most once. In its scope,
     * `onlyInitializing` functions can be used to initialize parent contracts.
     *
     * Similar to `reinitializer(1)`, except that in the context of a constructor an `initializer` may be invoked any
     * number of times. This behavior in the constructor can be useful during testing and is not expected to be used in
     * production.
     *
     * Emits an {Initialized} event.
     */
    modifier initializer() {
        // solhint-disable-next-line var-name-mixedcase
        InitializableStorage storage $ = _getInitializableStorage();

        // Cache values to avoid duplicated sloads
        bool isTopLevelCall = !$._initializing;
        uint64 initialized = $._initialized;

        // Allowed calls:
        // - initialSetup: the contract is not in the initializing state and no previous version was
        //                 initialized
        // - construction: the contract is initialized at version 1 (no reinitialization) and the
        //                 current contract is just being deployed
        bool initialSetup = initialized == 0 && isTopLevelCall;
        bool construction = initialized == 1 && address(this).code.length == 0;

        if (!initialSetup && !construction) {
            revert InvalidInitialization();
        }
        $._initialized = 1;
        if (isTopLevelCall) {
            $._initializing = true;
        }
        _;
        if (isTopLevelCall) {
            $._initializing = false;
            emit Initialized(1);
        }
    }

    /**
     * @dev A modifier that defines a protected reinitializer function that can be invoked at most once, and only if the
     * contract hasn't been initialized to a greater version before. In its scope, `onlyInitializing` functions can be
     * used to initialize parent contracts.
     *
     * A reinitializer may be used after the original initialization step. This is essential to configure modules that
     * are added through upgrades and that require initialization.
     *
     * When `version` is 1, this modifier is similar to `initializer`, except that functions marked with `reinitializer`
     * cannot be nested. If one is invoked in the context of another, execution will revert.
     *
     * Note that versions can jump in increments greater than 1; this implies that if multiple reinitializers coexist in
     * a contract, executing them in the right order is up to the developer or operator.
     *
     * WARNING: Setting the version to 2**64 - 1 will prevent any future reinitialization.
     *
     * Emits an {Initialized} event.
     */
    modifier reinitializer(uint64 version) {
        // solhint-disable-next-line var-name-mixedcase
        InitializableStorage storage $ = _getInitializableStorage();

        if ($._initializing || $._initialized >= version) {
            revert InvalidInitialization();
        }
        $._initialized = version;
        $._initializing = true;
        _;
        $._initializing = false;
        emit Initialized(version);
    }

    /**
     * @dev Modifier to protect an initialization function so that it can only be invoked by functions with the
     * {initializer} and {reinitializer} modifiers, directly or indirectly.
     */
    modifier onlyInitializing() {
        _checkInitializing();
        _;
    }

    /**
     * @dev Reverts if the contract is not in an initializing state. See {onlyInitializing}.
     */
    function _checkInitializing() internal view virtual {
        if (!_isInitializing()) {
            revert NotInitializing();
        }
    }

    /**
     * @dev Locks the contract, preventing any future reinitialization. This cannot be part of an initializer call.
     * Calling this in the constructor of a contract will prevent that contract from being initialized or reinitialized
     * to any version. It is recommended to use this to lock implementation contracts that are designed to be called
     * through proxies.
     *
     * Emits an {Initialized} event the first time it is successfully executed.
     */
    function _disableInitializers() internal virtual {
        // solhint-disable-next-line var-name-mixedcase
        InitializableStorage storage $ = _getInitializableStorage();

        if ($._initializing) {
            revert InvalidInitialization();
        }
        if ($._initialized != type(uint64).max) {
            $._initialized = type(uint64).max;
            emit Initialized(type(uint64).max);
        }
    }

    /**
     * @dev Returns the highest version that has been initialized. See {reinitializer}.
     */
    function _getInitializedVersion() internal view returns (uint64) {
        return _getInitializableStorage()._initialized;
    }

    /**
     * @dev Returns `true` if the contract is currently initializing. See {onlyInitializing}.
     */
    function _isInitializing() internal view returns (bool) {
        return _getInitializableStorage()._initializing;
    }

    /**
     * @dev Pointer to storage slot. Allows integrators to override it with a custom storage location.
     *
     * NOTE: Consider following the ERC-7201 formula to derive storage locations.
     */
    function _initializableStorageSlot() internal pure virtual returns (bytes32) {
        return INITIALIZABLE_STORAGE;
    }

    /**
     * @dev Returns a pointer to the storage namespace.
     */
    // solhint-disable-next-line var-name-mixedcase
    function _getInitializableStorage() private pure returns (InitializableStorage storage $) {
        bytes32 slot = _initializableStorageSlot();
        assembly {
            $.slot := slot
        }
    }
}

// File: @openzeppelin/contracts-upgradeable/utils/ContextUpgradeable.sol


// OpenZeppelin Contracts (last updated v5.0.1) (utils/Context.sol)

pragma solidity ^0.8.20;


/**
 * @dev Provides information about the current execution context, including the
 * sender of the transaction and its data. While these are generally available
 * via msg.sender and msg.data, they should not be accessed in such a direct
 * manner, since when dealing with meta-transactions the account sending and
 * paying for execution may not be the actual sender (as far as an application
 * is concerned).
 *
 * This contract is only required for intermediate, library-like contracts.
 */
abstract contract ContextUpgradeable is Initializable {
    function __Context_init() internal onlyInitializing {
    }

    function __Context_init_unchained() internal onlyInitializing {
    }
    function _msgSender() internal view virtual returns (address) {
        return msg.sender;
    }

    function _msgData() internal view virtual returns (bytes calldata) {
        return msg.data;
    }

    function _contextSuffixLength() internal view virtual returns (uint256) {
        return 0;
    }
}

// File: @openzeppelin/contracts-upgradeable/access/OwnableUpgradeable.sol


// OpenZeppelin Contracts (last updated v5.0.0) (access/Ownable.sol)

pragma solidity ^0.8.20;



/**
 * @dev Contract module which provides a basic access control mechanism, where
 * there is an account (an owner) that can be granted exclusive access to
 * specific functions.
 *
 * The initial owner is set to the address provided by the deployer. This can
 * later be changed with {transferOwnership}.
 *
 * This module is used through inheritance. It will make available the modifier
 * `onlyOwner`, which can be applied to your functions to restrict their use to
 * the owner.
 */
abstract contract OwnableUpgradeable is Initializable, ContextUpgradeable {
    /// @custom:storage-location erc7201:openzeppelin.storage.Ownable
    struct OwnableStorage {
        address _owner;
    }

    // keccak256(abi.encode(uint256(keccak256("openzeppelin.storage.Ownable")) - 1)) & ~bytes32(uint256(0xff))
    bytes32 private constant OwnableStorageLocation = 0x9016d09d72d40fdae2fd8ceac6b6234c7706214fd39c1cd1e609a0528c199300;

    function _getOwnableStorage() private pure returns (OwnableStorage storage $) {
        assembly {
            $.slot := OwnableStorageLocation
        }
    }

    /**
     * @dev The caller account is not authorized to perform an operation.
     */
    error OwnableUnauthorizedAccount(address account);

    /**
     * @dev The owner is not a valid owner account. (eg. `address(0)`)
     */
    error OwnableInvalidOwner(address owner);

    event OwnershipTransferred(address indexed previousOwner, address indexed newOwner);

    /**
     * @dev Initializes the contract setting the address provided by the deployer as the initial owner.
     */
    function __Ownable_init(address initialOwner) internal onlyInitializing {
        __Ownable_init_unchained(initialOwner);
    }

    function __Ownable_init_unchained(address initialOwner) internal onlyInitializing {
        if (initialOwner == address(0)) {
            revert OwnableInvalidOwner(address(0));
        }
        _transferOwnership(initialOwner);
    }

    /**
     * @dev Throws if called by any account other than the owner.
     */
    modifier onlyOwner() {
        _checkOwner();
        _;
    }

    /**
     * @dev Returns the address of the current owner.
     */
    function owner() public view virtual returns (address) {
        OwnableStorage storage $ = _getOwnableStorage();
        return $._owner;
    }

    /**
     * @dev Throws if the sender is not the owner.
     */
    function _checkOwner() internal view virtual {
        if (owner() != _msgSender()) {
            revert OwnableUnauthorizedAccount(_msgSender());
        }
    }

    /**
     * @dev Leaves the contract without owner. It will not be possible to call
     * `onlyOwner` functions. Can only be called by the current owner.
     *
     * NOTE: Renouncing ownership will leave the contract without an owner,
     * thereby disabling any functionality that is only available to the owner.
     */
    function renounceOwnership() public virtual onlyOwner {
        _transferOwnership(address(0));
    }

    /**
     * @dev Transfers ownership of the contract to a new account (`newOwner`).
     * Can only be called by the current owner.
     */
    function transferOwnership(address newOwner) public virtual onlyOwner {
        if (newOwner == address(0)) {
            revert OwnableInvalidOwner(address(0));
        }
        _transferOwnership(newOwner);
    }

    /**
     * @dev Transfers ownership of the contract to a new account (`newOwner`).
     * Internal function without access restriction.
     */
    function _transferOwnership(address newOwner) internal virtual {
        OwnableStorage storage $ = _getOwnableStorage();
        address oldOwner = $._owner;
        $._owner = newOwner;
        emit OwnershipTransferred(oldOwner, newOwner);
    }
}

// File: @openzeppelin/contracts-upgradeable/access/Ownable2StepUpgradeable.sol


// OpenZeppelin Contracts (last updated v5.1.0) (access/Ownable2Step.sol)

pragma solidity ^0.8.20;



/**
 * @dev Contract module which provides access control mechanism, where
 * there is an account (an owner) that can be granted exclusive access to
 * specific functions.
 *
 * This extension of the {Ownable} contract includes a two-step mechanism to transfer
 * ownership, where the new owner must call {acceptOwnership} in order to replace the
 * old one. This can help prevent common mistakes, such as transfers of ownership to
 * incorrect accounts, or to contracts that are unable to interact with the
 * permission system.
 *
 * The initial owner is specified at deployment time in the constructor for `Ownable`. This
 * can later be changed with {transferOwnership} and {acceptOwnership}.
 *
 * This module is used through inheritance. It will make available all functions
 * from parent (Ownable).
 */
abstract contract Ownable2StepUpgradeable is Initializable, OwnableUpgradeable {
    /// @custom:storage-location erc7201:openzeppelin.storage.Ownable2Step
    struct Ownable2StepStorage {
        address _pendingOwner;
    }

    // keccak256(abi.encode(uint256(keccak256("openzeppelin.storage.Ownable2Step")) - 1)) & ~bytes32(uint256(0xff))
    bytes32 private constant Ownable2StepStorageLocation = 0x237e158222e3e6968b72b9db0d8043aacf074ad9f650f0d1606b4d82ee432c00;

    function _getOwnable2StepStorage() private pure returns (Ownable2StepStorage storage $) {
        assembly {
            $.slot := Ownable2StepStorageLocation
        }
    }

    event OwnershipTransferStarted(address indexed previousOwner, address indexed newOwner);

    function __Ownable2Step_init() internal onlyInitializing {
    }

    function __Ownable2Step_init_unchained() internal onlyInitializing {
    }
    /**
     * @dev Returns the address of the pending owner.
     */
    function pendingOwner() public view virtual returns (address) {
        Ownable2StepStorage storage $ = _getOwnable2StepStorage();
        return $._pendingOwner;
    }

    /**
     * @dev Starts the ownership transfer of the contract to a new account. Replaces the pending transfer if there is one.
     * Can only be called by the current owner.
     *
     * Setting `newOwner` to the zero address is allowed; this can be used to cancel an initiated ownership transfer.
     */
    function transferOwnership(address newOwner) public virtual override onlyOwner {
        Ownable2StepStorage storage $ = _getOwnable2StepStorage();
        $._pendingOwner = newOwner;
        emit OwnershipTransferStarted(owner(), newOwner);
    }

    /**
     * @dev Transfers ownership of the contract to a new account (`newOwner`) and deletes any pending owner.
     * Internal function without access restriction.
     */
    function _transferOwnership(address newOwner) internal virtual override {
        Ownable2StepStorage storage $ = _getOwnable2StepStorage();
        delete $._pendingOwner;
        super._transferOwnership(newOwner);
    }

    /**
     * @dev The new owner accepts the ownership transfer.
     */
    function acceptOwnership() public virtual {
        address sender = _msgSender();
        if (pendingOwner() != sender) {
            revert OwnableUnauthorizedAccount(sender);
        }
        _transferOwnership(sender);
    }
}

// File: @openzeppelin/contracts-upgradeable/utils/PausableUpgradeable.sol


// OpenZeppelin Contracts (last updated v5.3.0) (utils/Pausable.sol)

pragma solidity ^0.8.20;



/**
 * @dev Contract module which allows children to implement an emergency stop
 * mechanism that can be triggered by an authorized account.
 *
 * This module is used through inheritance. It will make available the
 * modifiers `whenNotPaused` and `whenPaused`, which can be applied to
 * the functions of your contract. Note that they will not be pausable by
 * simply including this module, only once the modifiers are put in place.
 */
abstract contract PausableUpgradeable is Initializable, ContextUpgradeable {
    /// @custom:storage-location erc7201:openzeppelin.storage.Pausable
    struct PausableStorage {
        bool _paused;
    }

    // keccak256(abi.encode(uint256(keccak256("openzeppelin.storage.Pausable")) - 1)) & ~bytes32(uint256(0xff))
    bytes32 private constant PausableStorageLocation = 0xcd5ed15c6e187e77e9aee88184c21f4f2182ab5827cb3b7e07fbedcd63f03300;

    function _getPausableStorage() private pure returns (PausableStorage storage $) {
        assembly {
            $.slot := PausableStorageLocation
        }
    }

    /**
     * @dev Emitted when the pause is triggered by `account`.
     */
    event Paused(address account);

    /**
     * @dev Emitted when the pause is lifted by `account`.
     */
    event Unpaused(address account);

    /**
     * @dev The operation failed because the contract is paused.
     */
    error EnforcedPause();

    /**
     * @dev The operation failed because the contract is not paused.
     */
    error ExpectedPause();

    /**
     * @dev Modifier to make a function callable only when the contract is not paused.
     *
     * Requirements:
     *
     * - The contract must not be paused.
     */
    modifier whenNotPaused() {
        _requireNotPaused();
        _;
    }

    /**
     * @dev Modifier to make a function callable only when the contract is paused.
     *
     * Requirements:
     *
     * - The contract must be paused.
     */
    modifier whenPaused() {
        _requirePaused();
        _;
    }

    function __Pausable_init() internal onlyInitializing {
    }

    function __Pausable_init_unchained() internal onlyInitializing {
    }
    /**
     * @dev Returns true if the contract is paused, and false otherwise.
     */
    function paused() public view virtual returns (bool) {
        PausableStorage storage $ = _getPausableStorage();
        return $._paused;
    }

    /**
     * @dev Throws if the contract is paused.
     */
    function _requireNotPaused() internal view virtual {
        if (paused()) {
            revert EnforcedPause();
        }
    }

    /**
     * @dev Throws if the contract is not paused.
     */
    function _requirePaused() internal view virtual {
        if (!paused()) {
            revert ExpectedPause();
        }
    }

    /**
     * @dev Triggers stopped state.
     *
     * Requirements:
     *
     * - The contract must not be paused.
     */
    function _pause() internal virtual whenNotPaused {
        PausableStorage storage $ = _getPausableStorage();
        $._paused = true;
        emit Paused(_msgSender());
    }

    /**
     * @dev Returns to normal state.
     *
     * Requirements:
     *
     * - The contract must be paused.
     */
    function _unpause() internal virtual whenPaused {
        PausableStorage storage $ = _getPausableStorage();
        $._paused = false;
        emit Unpaused(_msgSender());
    }
}

// File: @openzeppelin/contracts-upgradeable/utils/ReentrancyGuardUpgradeable.sol


// OpenZeppelin Contracts (last updated v5.1.0) (utils/ReentrancyGuard.sol)

pragma solidity ^0.8.20;


/**
 * @dev Contract module that helps prevent reentrant calls to a function.
 *
 * Inheriting from `ReentrancyGuard` will make the {nonReentrant} modifier
 * available, which can be applied to functions to make sure there are no nested
 * (reentrant) calls to them.
 *
 * Note that because there is a single `nonReentrant` guard, functions marked as
 * `nonReentrant` may not call one another. This can be worked around by making
 * those functions `private`, and then adding `external` `nonReentrant` entry
 * points to them.
 *
 * TIP: If EIP-1153 (transient storage) is available on the chain you're deploying at,
 * consider using {ReentrancyGuardTransient} instead.
 *
 * TIP: If you would like to learn more about reentrancy and alternative ways
 * to protect against it, check out our blog post
 * https://blog.openzeppelin.com/reentrancy-after-istanbul/[Reentrancy After Istanbul].
 */
abstract contract ReentrancyGuardUpgradeable is Initializable {
    // Booleans are more expensive than uint256 or any type that takes up a full
    // word because each write operation emits an extra SLOAD to first read the
    // slot's contents, replace the bits taken up by the boolean, and then write
    // back. This is the compiler's defense against contract upgrades and
    // pointer aliasing, and it cannot be disabled.

    // The values being non-zero value makes deployment a bit more expensive,
    // but in exchange the refund on every call to nonReentrant will be lower in
    // amount. Since refunds are capped to a percentage of the total
    // transaction's gas, it is best to keep them low in cases like this one, to
    // increase the likelihood of the full refund coming into effect.
    uint256 private constant NOT_ENTERED = 1;
    uint256 private constant ENTERED = 2;

    /// @custom:storage-location erc7201:openzeppelin.storage.ReentrancyGuard
    struct ReentrancyGuardStorage {
        uint256 _status;
    }

    // keccak256(abi.encode(uint256(keccak256("openzeppelin.storage.ReentrancyGuard")) - 1)) & ~bytes32(uint256(0xff))
    bytes32 private constant ReentrancyGuardStorageLocation = 0x9b779b17422d0df92223018b32b4d1fa46e071723d6817e2486d003becc55f00;

    function _getReentrancyGuardStorage() private pure returns (ReentrancyGuardStorage storage $) {
        assembly {
            $.slot := ReentrancyGuardStorageLocation
        }
    }

    /**
     * @dev Unauthorized reentrant call.
     */
    error ReentrancyGuardReentrantCall();

    function __ReentrancyGuard_init() internal onlyInitializing {
        __ReentrancyGuard_init_unchained();
    }

    function __ReentrancyGuard_init_unchained() internal onlyInitializing {
        ReentrancyGuardStorage storage $ = _getReentrancyGuardStorage();
        $._status = NOT_ENTERED;
    }

    /**
     * @dev Prevents a contract from calling itself, directly or indirectly.
     * Calling a `nonReentrant` function from another `nonReentrant`
     * function is not supported. It is possible to prevent this from happening
     * by making the `nonReentrant` function external, and making it call a
     * `private` function that does the actual work.
     */
    modifier nonReentrant() {
        _nonReentrantBefore();
        _;
        _nonReentrantAfter();
    }

    function _nonReentrantBefore() private {
        ReentrancyGuardStorage storage $ = _getReentrancyGuardStorage();
        // On the first call to nonReentrant, _status will be NOT_ENTERED
        if ($._status == ENTERED) {
            revert ReentrancyGuardReentrantCall();
        }

        // Any calls to nonReentrant after this point will fail
        $._status = ENTERED;
    }

    function _nonReentrantAfter() private {
        ReentrancyGuardStorage storage $ = _getReentrancyGuardStorage();
        // By storing the original value once again, a refund is triggered (see
        // https://eips.ethereum.org/EIPS/eip-2200)
        $._status = NOT_ENTERED;
    }

    /**
     * @dev Returns true if the reentrancy guard is currently set to "entered", which indicates there is a
     * `nonReentrant` function in the call stack.
     */
    function _reentrancyGuardEntered() internal view returns (bool) {
        ReentrancyGuardStorage storage $ = _getReentrancyGuardStorage();
        return $._status == ENTERED;
    }
}

// File: @openzeppelin/contracts/token/ERC20/IERC20.sol


// OpenZeppelin Contracts (last updated v5.4.0) (token/ERC20/IERC20.sol)

pragma solidity >=0.4.16;

/**
 * @dev Interface of the ERC-20 standard as defined in the ERC.
 */
interface IERC20 {
    /**
     * @dev Emitted when `value` tokens are moved from one account (`from`) to
     * another (`to`).
     *
     * Note that `value` may be zero.
     */
    event Transfer(address indexed from, address indexed to, uint256 value);

    /**
     * @dev Emitted when the allowance of a `spender` for an `owner` is set by
     * a call to {approve}. `value` is the new allowance.
     */
    event Approval(address indexed owner, address indexed spender, uint256 value);

    /**
     * @dev Returns the value of tokens in existence.
     */
    function totalSupply() external view returns (uint256);

    /**
     * @dev Returns the value of tokens owned by `account`.
     */
    function balanceOf(address account) external view returns (uint256);

    /**
     * @dev Moves a `value` amount of tokens from the caller's account to `to`.
     *
     * Returns a boolean value indicating whether the operation succeeded.
     *
     * Emits a {Transfer} event.
     */
    function transfer(address to, uint256 value) external returns (bool);

    /**
     * @dev Returns the remaining number of tokens that `spender` will be
     * allowed to spend on behalf of `owner` through {transferFrom}. This is
     * zero by default.
     *
     * This value changes when {approve} or {transferFrom} are called.
     */
    function allowance(address owner, address spender) external view returns (uint256);

    /**
     * @dev Sets a `value` amount of tokens as the allowance of `spender` over the
     * caller's tokens.
     *
     * Returns a boolean value indicating whether the operation succeeded.
     *
     * IMPORTANT: Beware that changing an allowance with this method brings the risk
     * that someone may use both the old and the new allowance by unfortunate
     * transaction ordering. One possible solution to mitigate this race
     * condition is to first reduce the spender's allowance to 0 and set the
     * desired value afterwards:
     * https://github.com/ethereum/EIPs/issues/20#issuecomment-263524729
     *
     * Emits an {Approval} event.
     */
    function approve(address spender, uint256 value) external returns (bool);

    /**
     * @dev Moves a `value` amount of tokens from `from` to `to` using the
     * allowance mechanism. `value` is then deducted from the caller's
     * allowance.
     *
     * Returns a boolean value indicating whether the operation succeeded.
     *
     * Emits a {Transfer} event.
     */
    function transferFrom(address from, address to, uint256 value) external returns (bool);
}

// File: @openzeppelin/contracts/interfaces/IERC20.sol


// OpenZeppelin Contracts (last updated v5.4.0) (interfaces/IERC20.sol)

pragma solidity >=0.4.16;


// File: @openzeppelin/contracts/utils/introspection/IERC165.sol


// OpenZeppelin Contracts (last updated v5.4.0) (utils/introspection/IERC165.sol)

pragma solidity >=0.4.16;

/**
 * @dev Interface of the ERC-165 standard, as defined in the
 * https://eips.ethereum.org/EIPS/eip-165[ERC].
 *
 * Implementers can declare support of contract interfaces, which can then be
 * queried by others ({ERC165Checker}).
 *
 * For an implementation, see {ERC165}.
 */
interface IERC165 {
    /**
     * @dev Returns true if this contract implements the interface defined by
     * `interfaceId`. See the corresponding
     * https://eips.ethereum.org/EIPS/eip-165#how-interfaces-are-identified[ERC section]
     * to learn more about how these ids are created.
     *
     * This function call must use less than 30 000 gas.
     */
    function supportsInterface(bytes4 interfaceId) external view returns (bool);
}

// File: @openzeppelin/contracts/interfaces/IERC165.sol


// OpenZeppelin Contracts (last updated v5.4.0) (interfaces/IERC165.sol)

pragma solidity >=0.4.16;


// File: @openzeppelin/contracts/interfaces/IERC1363.sol


// OpenZeppelin Contracts (last updated v5.4.0) (interfaces/IERC1363.sol)

pragma solidity >=0.6.2;



/**
 * @title IERC1363
 * @dev Interface of the ERC-1363 standard as defined in the https://eips.ethereum.org/EIPS/eip-1363[ERC-1363].
 *
 * Defines an extension interface for ERC-20 tokens that supports executing code on a recipient contract
 * after `transfer` or `transferFrom`, or code on a spender contract after `approve`, in a single transaction.
 */
interface IERC1363 is IERC20, IERC165 {
    /*
     * Note: the ERC-165 identifier for this interface is 0xb0202a11.
     * 0xb0202a11 ===
     *   bytes4(keccak256('transferAndCall(address,uint256)')) ^
     *   bytes4(keccak256('transferAndCall(address,uint256,bytes)')) ^
     *   bytes4(keccak256('transferFromAndCall(address,address,uint256)')) ^
     *   bytes4(keccak256('transferFromAndCall(address,address,uint256,bytes)')) ^
     *   bytes4(keccak256('approveAndCall(address,uint256)')) ^
     *   bytes4(keccak256('approveAndCall(address,uint256,bytes)'))
     */

    /**
     * @dev Moves a `value` amount of tokens from the caller's account to `to`
     * and then calls {IERC1363Receiver-onTransferReceived} on `to`.
     * @param to The address which you want to transfer to.
     * @param value The amount of tokens to be transferred.
     * @return A boolean value indicating whether the operation succeeded unless throwing.
     */
    function transferAndCall(address to, uint256 value) external returns (bool);

    /**
     * @dev Moves a `value` amount of tokens from the caller's account to `to`
     * and then calls {IERC1363Receiver-onTransferReceived} on `to`.
     * @param to The address which you want to transfer to.
     * @param value The amount of tokens to be transferred.
     * @param data Additional data with no specified format, sent in call to `to`.
     * @return A boolean value indicating whether the operation succeeded unless throwing.
     */
    function transferAndCall(address to, uint256 value, bytes calldata data) external returns (bool);

    /**
     * @dev Moves a `value` amount of tokens from `from` to `to` using the allowance mechanism
     * and then calls {IERC1363Receiver-onTransferReceived} on `to`.
     * @param from The address which you want to send tokens from.
     * @param to The address which you want to transfer to.
     * @param value The amount of tokens to be transferred.
     * @return A boolean value indicating whether the operation succeeded unless throwing.
     */
    function transferFromAndCall(address from, address to, uint256 value) external returns (bool);

    /**
     * @dev Moves a `value` amount of tokens from `from` to `to` using the allowance mechanism
     * and then calls {IERC1363Receiver-onTransferReceived} on `to`.
     * @param from The address which you want to send tokens from.
     * @param to The address which you want to transfer to.
     * @param value The amount of tokens to be transferred.
     * @param data Additional data with no specified format, sent in call to `to`.
     * @return A boolean value indicating whether the operation succeeded unless throwing.
     */
    function transferFromAndCall(address from, address to, uint256 value, bytes calldata data) external returns (bool);

    /**
     * @dev Sets a `value` amount of tokens as the allowance of `spender` over the
     * caller's tokens and then calls {IERC1363Spender-onApprovalReceived} on `spender`.
     * @param spender The address which will spend the funds.
     * @param value The amount of tokens to be spent.
     * @return A boolean value indicating whether the operation succeeded unless throwing.
     */
    function approveAndCall(address spender, uint256 value) external returns (bool);

    /**
     * @dev Sets a `value` amount of tokens as the allowance of `spender` over the
     * caller's tokens and then calls {IERC1363Spender-onApprovalReceived} on `spender`.
     * @param spender The address which will spend the funds.
     * @param value The amount of tokens to be spent.
     * @param data Additional data with no specified format, sent in call to `spender`.
     * @return A boolean value indicating whether the operation succeeded unless throwing.
     */
    function approveAndCall(address spender, uint256 value, bytes calldata data) external returns (bool);
}

// File: @openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol


// OpenZeppelin Contracts (last updated v5.3.0) (token/ERC20/utils/SafeERC20.sol)

pragma solidity ^0.8.20;



/**
 * @title SafeERC20
 * @dev Wrappers around ERC-20 operations that throw on failure (when the token
 * contract returns false). Tokens that return no value (and instead revert or
 * throw on failure) are also supported, non-reverting calls are assumed to be
 * successful.
 * To use this library you can add a `using SafeERC20 for IERC20;` statement to your contract,
 * which allows you to call the safe operations as `token.safeTransfer(...)`, etc.
 */
library SafeERC20 {
    /**
     * @dev An operation with an ERC-20 token failed.
     */
    error SafeERC20FailedOperation(address token);

    /**
     * @dev Indicates a failed `decreaseAllowance` request.
     */
    error SafeERC20FailedDecreaseAllowance(address spender, uint256 currentAllowance, uint256 requestedDecrease);

    /**
     * @dev Transfer `value` amount of `token` from the calling contract to `to`. If `token` returns no value,
     * non-reverting calls are assumed to be successful.
     */
    function safeTransfer(IERC20 token, address to, uint256 value) internal {
        _callOptionalReturn(token, abi.encodeCall(token.transfer, (to, value)));
    }

    /**
     * @dev Transfer `value` amount of `token` from `from` to `to`, spending the approval given by `from` to the
     * calling contract. If `token` returns no value, non-reverting calls are assumed to be successful.
     */
    function safeTransferFrom(IERC20 token, address from, address to, uint256 value) internal {
        _callOptionalReturn(token, abi.encodeCall(token.transferFrom, (from, to, value)));
    }

    /**
     * @dev Variant of {safeTransfer} that returns a bool instead of reverting if the operation is not successful.
     */
    function trySafeTransfer(IERC20 token, address to, uint256 value) internal returns (bool) {
        return _callOptionalReturnBool(token, abi.encodeCall(token.transfer, (to, value)));
    }

    /**
     * @dev Variant of {safeTransferFrom} that returns a bool instead of reverting if the operation is not successful.
     */
    function trySafeTransferFrom(IERC20 token, address from, address to, uint256 value) internal returns (bool) {
        return _callOptionalReturnBool(token, abi.encodeCall(token.transferFrom, (from, to, value)));
    }

    /**
     * @dev Increase the calling contract's allowance toward `spender` by `value`. If `token` returns no value,
     * non-reverting calls are assumed to be successful.
     *
     * IMPORTANT: If the token implements ERC-7674 (ERC-20 with temporary allowance), and if the "client"
     * smart contract uses ERC-7674 to set temporary allowances, then the "client" smart contract should avoid using
     * this function. Performing a {safeIncreaseAllowance} or {safeDecreaseAllowance} operation on a token contract
     * that has a non-zero temporary allowance (for that particular owner-spender) will result in unexpected behavior.
     */
    function safeIncreaseAllowance(IERC20 token, address spender, uint256 value) internal {
        uint256 oldAllowance = token.allowance(address(this), spender);
        forceApprove(token, spender, oldAllowance + value);
    }

    /**
     * @dev Decrease the calling contract's allowance toward `spender` by `requestedDecrease`. If `token` returns no
     * value, non-reverting calls are assumed to be successful.
     *
     * IMPORTANT: If the token implements ERC-7674 (ERC-20 with temporary allowance), and if the "client"
     * smart contract uses ERC-7674 to set temporary allowances, then the "client" smart contract should avoid using
     * this function. Performing a {safeIncreaseAllowance} or {safeDecreaseAllowance} operation on a token contract
     * that has a non-zero temporary allowance (for that particular owner-spender) will result in unexpected behavior.
     */
    function safeDecreaseAllowance(IERC20 token, address spender, uint256 requestedDecrease) internal {
        unchecked {
            uint256 currentAllowance = token.allowance(address(this), spender);
            if (currentAllowance < requestedDecrease) {
                revert SafeERC20FailedDecreaseAllowance(spender, currentAllowance, requestedDecrease);
            }
            forceApprove(token, spender, currentAllowance - requestedDecrease);
        }
    }

    /**
     * @dev Set the calling contract's allowance toward `spender` to `value`. If `token` returns no value,
     * non-reverting calls are assumed to be successful. Meant to be used with tokens that require the approval
     * to be set to zero before setting it to a non-zero value, such as USDT.
     *
     * NOTE: If the token implements ERC-7674, this function will not modify any temporary allowance. This function
     * only sets the "standard" allowance. Any temporary allowance will remain active, in addition to the value being
     * set here.
     */
    function forceApprove(IERC20 token, address spender, uint256 value) internal {
        bytes memory approvalCall = abi.encodeCall(token.approve, (spender, value));

        if (!_callOptionalReturnBool(token, approvalCall)) {
            _callOptionalReturn(token, abi.encodeCall(token.approve, (spender, 0)));
            _callOptionalReturn(token, approvalCall);
        }
    }

    /**
     * @dev Performs an {ERC1363} transferAndCall, with a fallback to the simple {ERC20} transfer if the target has no
     * code. This can be used to implement an {ERC721}-like safe transfer that rely on {ERC1363} checks when
     * targeting contracts.
     *
     * Reverts if the returned value is other than `true`.
     */
    function transferAndCallRelaxed(IERC1363 token, address to, uint256 value, bytes memory data) internal {
        if (to.code.length == 0) {
            safeTransfer(token, to, value);
        } else if (!token.transferAndCall(to, value, data)) {
            revert SafeERC20FailedOperation(address(token));
        }
    }

    /**
     * @dev Performs an {ERC1363} transferFromAndCall, with a fallback to the simple {ERC20} transferFrom if the target
     * has no code. This can be used to implement an {ERC721}-like safe transfer that rely on {ERC1363} checks when
     * targeting contracts.
     *
     * Reverts if the returned value is other than `true`.
     */
    function transferFromAndCallRelaxed(
        IERC1363 token,
        address from,
        address to,
        uint256 value,
        bytes memory data
    ) internal {
        if (to.code.length == 0) {
            safeTransferFrom(token, from, to, value);
        } else if (!token.transferFromAndCall(from, to, value, data)) {
            revert SafeERC20FailedOperation(address(token));
        }
    }

    /**
     * @dev Performs an {ERC1363} approveAndCall, with a fallback to the simple {ERC20} approve if the target has no
     * code. This can be used to implement an {ERC721}-like safe transfer that rely on {ERC1363} checks when
     * targeting contracts.
     *
     * NOTE: When the recipient address (`to`) has no code (i.e. is an EOA), this function behaves as {forceApprove}.
     * Opposedly, when the recipient address (`to`) has code, this function only attempts to call {ERC1363-approveAndCall}
     * once without retrying, and relies on the returned value to be true.
     *
     * Reverts if the returned value is other than `true`.
     */
    function approveAndCallRelaxed(IERC1363 token, address to, uint256 value, bytes memory data) internal {
        if (to.code.length == 0) {
            forceApprove(token, to, value);
        } else if (!token.approveAndCall(to, value, data)) {
            revert SafeERC20FailedOperation(address(token));
        }
    }

    /**
     * @dev Imitates a Solidity high-level call (i.e. a regular function call to a contract), relaxing the requirement
     * on the return value: the return value is optional (but if data is returned, it must not be false).
     * @param token The token targeted by the call.
     * @param data The call data (encoded using abi.encode or one of its variants).
     *
     * This is a variant of {_callOptionalReturnBool} that reverts if call fails to meet the requirements.
     */
    function _callOptionalReturn(IERC20 token, bytes memory data) private {
        uint256 returnSize;
        uint256 returnValue;
        assembly ("memory-safe") {
            let success := call(gas(), token, 0, add(data, 0x20), mload(data), 0, 0x20)
            // bubble errors
            if iszero(success) {
                let ptr := mload(0x40)
                returndatacopy(ptr, 0, returndatasize())
                revert(ptr, returndatasize())
            }
            returnSize := returndatasize()
            returnValue := mload(0)
        }

        if (returnSize == 0 ? address(token).code.length == 0 : returnValue != 1) {
            revert SafeERC20FailedOperation(address(token));
        }
    }

    /**
     * @dev Imitates a Solidity high-level call (i.e. a regular function call to a contract), relaxing the requirement
     * on the return value: the return value is optional (but if data is returned, it must not be false).
     * @param token The token targeted by the call.
     * @param data The call data (encoded using abi.encode or one of its variants).
     *
     * This is a variant of {_callOptionalReturn} that silently catches all reverts and returns a bool instead.
     */
    function _callOptionalReturnBool(IERC20 token, bytes memory data) private returns (bool) {
        bool success;
        uint256 returnSize;
        uint256 returnValue;
        assembly ("memory-safe") {
            success := call(gas(), token, 0, add(data, 0x20), mload(data), 0, 0x20)
            returnSize := returndatasize()
            returnValue := mload(0)
        }
        return success && (returnSize == 0 ? address(token).code.length > 0 : returnValue == 1);
    }
}

// File: contracts/utils/cryptography/Hash.sol


pragma solidity 0.8.24;

library Hash {

    function hash(bytes32 left, bytes32 right) internal pure returns (bytes32) {
        if (left == 0x0 && right == 0x0) {
            return 0x0;
        } else if (left == 0x0) {
            return keccak256(abi.encodePacked(right));
        } else if (right == 0x0) {
            return keccak256(abi.encodePacked(left));
        } else {
            return keccak256(abi.encodePacked(left, right));
        }
    }

    function hashGtvBytes32Leaf(bytes32 value) internal pure returns (bytes32) {
        return sha256(abi.encodePacked(
                uint8(0x1),  // Gtv merkle tree leaf prefix
                uint8(0xA1), // // Gtv ByteArray tag: CONTEXT_CLASS, CONSTRUCTED, 1
                uint8(32 + 2),
                uint8(0x4), // DER ByteArray tag
                uint8(32),
                value
        ));
    }

    function hashGtvBytes64Leaf(bytes memory value) internal pure returns (bytes32) {
        if (value.length != 64) {
            revert("Hash: value must be 64 bytes long");
        }
        return sha256(abi.encodePacked(
                uint8(0x1),  // Gtv merkle tree leaf prefix
                uint8(0xA1), // // Gtv ByteArray tag: CONTEXT_CLASS, CONSTRUCTED, 1
                uint8(64 + 2),
                uint8(0x4), // DER ByteArray tag
                uint8(64),
                value
        ));
    }

    function hashGtvIntegerLeaf(uint value) internal pure returns (bytes32) {
        uint8 nbytes = 1;
        uint remainingValue = value >> 8; // minimal length is 1 so we skip the first byte
        while (remainingValue > 0) {
            nbytes += 1;
            remainingValue = remainingValue >> 8;
        }
        bytes memory b = new bytes(nbytes);
        remainingValue = value;
        for (uint8 i = 1; i <= nbytes; i++) {
            uint8 v = uint8(remainingValue & 0xFF);
            b[nbytes - i] = bytes1(v);
            remainingValue = remainingValue >> 8;
        }

        if (uint8(b[0]) & 0x80 > 0) {
            return sha256(abi.encodePacked(
                uint8(0x1),  // Gtv merkle tree leaf prefix
                uint8(0xA3), // GtvInteger tag: CONTEXT_CLASS, CONSTRUCTED, 3
                uint8(nbytes + 3),
                uint8(0x2), // DER integer tag
                nbytes+1,
                uint8(0),
                b
            ));
        } 

        return sha256(abi.encodePacked(
                uint8(0x1),  // Gtv merkle tree leaf prefix
                uint8(0xA3), // GtvInteger tag: CONTEXT_CLASS, CONSTRUCTED, 3
                uint8(nbytes + 2),
                uint8(0x2), // DER integer tag
                nbytes,
                b
            ));
    }

}
// File: contracts/utils/cryptography/MerkleProof.sol


pragma solidity 0.8.24;


library MerkleProof {
    /**
     * @dev verify merkle proof using keccak256
     */
    function verify(bytes32[] memory proofs, bytes32 leaf, uint position, bytes32 rootHash) internal pure returns (bool) {
        if (leaf == 0x0 || position >= (1 << proofs.length)) {
            return false;
        }
        bytes32 r = leaf;
        for (uint i = 0; i < proofs.length; i++) {
            uint b = position & (1 << i);
            if (b == 0) {
                r = Hash.hash(r, proofs[i]);
            } else {
                r = Hash.hash(proofs[i], r);
            }
        }
        return (r == rootHash);
    }

    /**
     * @dev verify merkle proof using sha256
     * specific for postchain block header extra data in dictionary data format
     */
    function verifySHA256(bytes32[] memory proofs, bytes32 leaf, uint position, bytes32 rootHash) internal pure returns (bool) {
        if (position >= (1 << proofs.length)) {
            return false;
        }
        bytes32 r = leaf; // hashed leaf
        uint last = proofs.length-1;
        for (uint i = 0; i < last; i++) {
            uint b = position & (1 << i);
            if (b == 0) {
                r = sha256(abi.encodePacked(uint8(0x00), r, proofs[i]));
            } else {
                r = sha256(abi.encodePacked(uint8(0x00), proofs[i], r));
            }
        }
        // the last node is fixed in dictionary format, prefix = 0x8
        uint p = position & (1 << last);
        if (p == 0) {
            r = sha256(abi.encodePacked(uint8(0x08), r, proofs[last]));
        } else {
            r = sha256(abi.encodePacked(uint8(0x08), proofs[last], r));
        }
        return (r == rootHash);
    }

    function bytesToBytes32(bytes memory b, uint offset) internal pure returns (bytes32) {
        bytes32 out;

        for (uint i = 0; i < 32; i++) {
            out |= bytes32(b[offset + i] & 0xFF) >> (i * 8);
        }
        return out;
    }
}
// File: contracts/Data.sol


pragma solidity 0.8.24;

library Data {
    struct ExtraProofData {
        bytes leaf;
        bytes32 hashedLeaf;
        uint position;
        bytes32 extraRoot;
        bytes32[] extraMerkleProofs;
    }

    struct Proof {
        bytes32 leaf;
        uint position;
        bytes32[] merkleProofs;
    }
}
// File: contracts/Postchain.sol


pragma solidity 0.8.24;

// Interfaces


// Internal libraries




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
        bytes32 extraDataHashedLeaf; // @dev This field corresponds to the extra data Merkle root hash in Postchain
    }

    struct PostchainBlock {
        uint height;
        bytes32 blockRid;
        bytes32 extraDataHashedLeaf; // @dev This field corresponds to the extra data Merkle root hash in Postchain
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

    function verifyDiscriminator(uint256 networkId, address contractAddress, uint256 discriminator) internal pure {
        uint256 networkDiscriminator = networkId << 160;
        uint256 networkContractDiscriminator = networkDiscriminator + uint160(contractAddress);

        bool isValid = (discriminator == networkDiscriminator)
            || (discriminator == networkContractDiscriminator);

        require(isValid, "Postchain: Invalid discriminator. Please verify the network ID and bridge contract.");
    }
}

// File: contracts/IValidator.sol


pragma solidity 0.8.24;

interface IValidator {
    function isValidSignatures(bytes32 hash, bytes[] memory signatures, address[] memory signers) external view returns (bool);

    function isValidator(address _addr) external view returns (bool);
}

// File: contracts/TokenBridge.sol


pragma solidity 0.8.24;

// Upgradeable implementations






// Internal libraries



// This contract is upgradeable. This imposes restrictions on how storage layout can be modified once it is deployed
// Some instructions are also not allowed. Read more at: https://docs.openzeppelin.com/upgrades-plugins/1.x/writing-upgradeable
// Note: To enhance the security & decentralization, we should call transferOwnership() to external multi-sig owner after deploy the smart contract
contract TokenBridge is Initializable, PausableUpgradeable, Ownable2StepUpgradeable, ReentrancyGuardUpgradeable {

    using Postchain for bytes32;
    using MerkleProof for bytes32[];
    using SafeERC20 for IERC20;

    mapping(IERC20 => bool) public _allowedToken;
    mapping(bytes32 => Withdraw) public _withdraw;
    IValidator public validator;
    uint256 public networkId;
    bool public isMassExit;
    Postchain.PostchainBlock public massExitBlock;
    uint256 public withdrawTimeOffset;      // @dev Block time in seconds

    bytes32 internal blockchainRid;         // @dev Postchain/Chromia blockchain RID
    bool public isBlockchainRidFinalized;   // @dev Flag to track if blockchain RID is finalized

    // Each postchain event will be used to claim only one time.
    mapping(bytes32 => bool) internal _events;

    uint8 public version;                   // @dev Bridge contract version.

    enum Status {
        Uninitialized, // to prevent creating empty Withdraw objects by unused hash in unpendingWithdraw()
        Pending,
        Withdrawable,
        Withdrawn,
        PostchainWithdrawn
    }

    struct Withdraw {
        IERC20 token;
        address beneficiary;
        uint256 amount;
        uint256 block_time;
        uint postchain_height;
        Status status;
    }

    event Initialize(IValidator indexed _validator, uint256 _withdrawTimeOffset, uint8 version);
    event SetBlockchainRid(bytes32 rid);
    event BlockchainRidFinalized(bytes32 rid);
    event AllowToken(IERC20 indexed token);
    event TriggerMassExit(uint indexed height, bytes32 indexed blockRid);
    event PendingWithdraw(bytes32 indexed hash);
    event UnpendingWithdraw(bytes32 indexed hash);
    event FundedERC20(address indexed sender, IERC20 indexed token, uint amount);
    event DepositedERC20(address indexed sender, IERC20 indexed token, uint amount, bytes32 accountID);
    event WithdrawRequest(address indexed beneficiary, IERC20 indexed token, uint256 value, uint height, bytes32 blockRid);
    event WithdrawRequestHash(bytes32 indexed hash);
    event Withdrawal(address indexed beneficiary, IERC20 indexed token, uint256 value);
    event WithdrawalHash(bytes32 indexed hash);
    event WithdrawalToPostchain(bytes32 indexed hash);
    event LinkAccountID(address indexed sender, bytes32 accountID, bool isContract);

    modifier isAllowToken(IERC20 token) {
        require(_allowedToken[token], "TokenBridge: not allow token");
        _;
    }

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

    function initialize(IValidator _validator, uint256 _withdrawTimeOffset) public initializer {
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
        withdrawTimeOffset = _withdrawTimeOffset;
        isBlockchainRidFinalized = false;
        version = 3;
        emit Initialize(_validator, _withdrawTimeOffset, version);
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

    function pendingWithdraw(bytes32 _hash) onlyOwner public {
        require(_hash != bytes32(0), "TokenBridge: event hash is invalid");
        Withdraw storage wd = _withdraw[_hash];
        require(wd.status == Status.Withdrawable, "TokenBridge: withdraw request status is not withdrawable");
        wd.status = Status.Pending;
        emit PendingWithdraw(_hash);
    }

    function unpendingWithdraw(bytes32 _hash) public onlyOwner {
        require(_hash != bytes32(0), "TokenBridge: event hash is invalid");
        Withdraw storage wd = _withdraw[_hash];
        require(wd.status == Status.Pending, "TokenBridge: withdraw request status is not pending");
        wd.status = Status.Withdrawable;
        emit UnpendingWithdraw(_hash);
    }

    /**
     * @dev admin need to fund enough token for bridge; otherwise, user cannot claim
     * and they might need to withdraw back to postchain.
     */
    function fund(IERC20 token, uint256 amount) public virtual isAllowToken(token) onlyOwner returns (bool) {
        token.safeTransferFrom(msg.sender, address(this), amount);
        emit FundedERC20(msg.sender, token, amount);
        return true;
    }

    function deposit(IERC20 token, uint256 amount) public isAllowToken(token) whenNotPaused whenNotMassExit onlyEOA returns (bool) {
        transferDeposit(token, amount);
        emit DepositedERC20(msg.sender, token, amount, 0x0); // accountID will be determined from sender
        return true;
    }

    function isContract(address addr) internal view returns (bool) {
        // Note: We are aware of the fact that this might return false even when the address is a contract.
        // It is fine for our purposes. We want to prevent EOA from calling depositToAccountID.
        return addr.code.length > 0;
    }

    modifier onlyContract() {
        require(isContract(msg.sender), "TokenBridge: only contract can call this function");
        _;
    }

    modifier onlyEOA() {
        require(!isContract(msg.sender), "TokenBridge: only EOA can call this function");
        _;
    }

    function depositToAccountID(IERC20 token, uint256 amount, bytes32 accountID) public
        isAllowToken(token)
        whenNotPaused
        whenNotMassExit
        onlyContract() // cannot be called from EOA for security reasons
        returns (bool)
    {
        require(accountID != bytes32(0), "TokenBridge: invalid accountID, cannot be zero.");
        transferDeposit(token, amount);
        emit DepositedERC20(msg.sender, token, amount, accountID);
        return true;
    }

    function linkAccountID(bytes32 accountID) external {
        emit LinkAccountID(msg.sender, accountID, isContract(msg.sender));
    }

    function transferDeposit(IERC20 token, uint256 amount) internal virtual {
        token.safeTransferFrom(msg.sender, address(this), amount);
    }

    /**
     * @dev signers should be order ascending
     */
    function withdrawRequest(
        bytes memory _event,
        Data.Proof memory eventProof,
        bytes memory blockHeader,
        bytes[] memory sigs,
        address[] memory signers,
        Data.ExtraProofData memory extraProof
    ) external whenNotMassExit whenNotPaused nonReentrant {
        (uint height, bytes32 blockRid) = _withdrawRequest(eventProof, blockHeader, sigs, signers, extraProof);
        _events[eventProof.leaf] = _updateWithdraw(eventProof.leaf, _event, height, blockRid); // mark the event hash was already used.
    }

    function _withdrawRequest(
        Data.Proof memory eventProof,
        bytes memory blockHeader,
        bytes[] memory sigs,
        address[] memory signers,
        Data.ExtraProofData memory extraProof
    ) internal view returns (uint, bytes32) {
        require(blockchainRid != bytes32(0), "TokenBridge: blockchain rid is not set");
        require(_events[eventProof.leaf] == false, "TokenBridge: event hash was already used");

        require(Hash.hashGtvBytes64Leaf(extraProof.leaf) == extraProof.hashedLeaf, "Postchain: invalid EIF extra data");
        (uint height, bytes32 blockRid) = Postchain.verifyBlockHeader(blockchainRid, blockHeader, extraProof, Postchain.EIF_KEY_MERKLE_HASH);
        bytes32 eventRoot = MerkleProof.bytesToBytes32(extraProof.leaf, 0);
        if (!validator.isValidSignatures(blockRid, sigs, signers)) revert("TokenBridge: block signature is invalid");
        if (!MerkleProof.verify(eventProof.merkleProofs, eventProof.leaf, eventProof.position, eventRoot)) revert("TokenBridge: invalid merkle proof");

        return (height, blockRid);
    }

    function _updateWithdraw(bytes32 hash, bytes memory _event, uint height, bytes32 blockRid) internal returns (bool) {
        Withdraw storage wd = _withdraw[hash];
        {
            (IERC20 token, address beneficiary, uint256 amount, uint256 discriminator) = hash.verifyEvent(_event);
            Postchain.verifyDiscriminator(networkId, address(this), discriminator);
            require(_allowedToken[token], "TokenBridge: not allow token");
            require(amount > 0, "TokenBridge: invalid amount to make request withdraw");
            wd.token = token;
            wd.beneficiary = beneficiary;
            wd.amount = amount;
            wd.postchain_height = height;
            wd.block_time = block.timestamp + withdrawTimeOffset;
            wd.status = Status.Withdrawable;
            _withdraw[hash] = wd;
            emit WithdrawRequest(beneficiary, token, amount, height, blockRid);
            emit WithdrawRequestHash(hash);
        }
        return true;
    }

    function withdraw(bytes32 _hash, address payable beneficiary) external whenNotMassExit whenNotPaused nonReentrant {
        Withdraw storage wd = _withdraw[_hash];
        require(wd.beneficiary == beneficiary, "TokenBridge: no fund for the beneficiary");
        require(wd.block_time <= block.timestamp, "TokenBridge: not mature enough to withdraw the fund");
        require(wd.status == Status.Withdrawable, "TokenBridge: fund is pending or was already claimed");
        wd.status = Status.Withdrawn;
        uint value = wd.amount;
        wd.amount = 0;
        // only support user to withdraw the token that be funded enough on the EVM bridge
        transferWithdraw(wd.token, beneficiary, value);
        emit Withdrawal(beneficiary, wd.token, value);
        emit WithdrawalHash(_hash);
    }

    function transferWithdraw(IERC20 token, address beneficiary, uint value) internal virtual {
        token.safeTransfer(beneficiary, value);
    }

    /**
     * @dev user can withdraw token back to postchain if they cannot withdraw on EVM chain
     */
    function withdrawToPostchain(bytes32 _hash) external whenNotPaused nonReentrant {
        Withdraw storage wd = _withdraw[_hash];
        require(wd.beneficiary == msg.sender, "TokenBridge: no fund for the beneficiary");
        require(wd.block_time <= block.timestamp, "TokenBridge: not mature enough to withdraw the fund");
        require(wd.status == Status.Withdrawable, "TokenBridge: fund is pending or was already claimed");
        wd.status = Status.PostchainWithdrawn;
        uint amount = wd.amount;
        wd.amount = 0;
        emit DepositedERC20(msg.sender, wd.token, amount, 0x0); // accountID will be determined from sender
        emit WithdrawalToPostchain(_hash);
    }
}

// File: contracts/ChromiaTokenBridge.sol


pragma solidity 0.8.24;

// Upgradeable implementations






// Internal libraries



interface ITokenMinter {
    function burn(uint256 amount) external;

    function mint(address to, uint256 amount) external;

    function transferMintRole(address newOwner) external;

    function finishTransferMintRole() external;
}

// This contract is upgradeable. This imposes restrictions on how storage layout can be modified once it is deployed
// Some instructions are also not allowed. Read more at: https://docs.openzeppelin.com/upgrades-plugins/1.x/writing-upgradeable
// Note: To enhance the security & decentralization, we should call transferOwnership() to external multi-sig owner after deploy the smart contract
contract ChromiaTokenBridge is TokenBridge {
    ITokenMinter public tokenMinter;
    using Postchain for bytes32;
    using MerkleProof for bytes32[];
    using SafeERC20 for IERC20;

    function setTokenMinter(ITokenMinter _tokenMinter) external onlyOwner {
        tokenMinter = _tokenMinter;
    }

    function transferDeposit(IERC20 token, uint256 amount) internal override {
        token.safeTransferFrom(msg.sender, address(tokenMinter), amount);
        tokenMinter.burn(amount);
    }

    function transferWithdraw(IERC20 token, address beneficiary, uint value) internal override {
        tokenMinter.mint(beneficiary, value);
    }

    function fund(IERC20 token, uint256 amount) public override returns (bool) {
        revert("ChromiaTokenBridge: direct funding is not supported");
    }
}
