// SPDX-License-Identifier: GPL-3.0-only
pragma solidity 0.8.24;

import "../../crc2/NFTBridge.sol";

contract NFTBridgeDelegator {
    NFTBridge private immutable _bridge;

    constructor(NFTBridge bridge) {
        _bridge = bridge;
    }

    function depositERC721(address contractAddress, uint256[] calldata tokenIds) external returns (bool) {
        return _bridge.depositERC721(contractAddress, tokenIds);
    }

    function depositERC1155(address contractAddress, uint256[] calldata tokenIds, uint256[] calldata amounts) external returns (bool) {
        return _bridge.depositERC1155(contractAddress, tokenIds, amounts);
    }
}
