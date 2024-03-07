package net.postchain.eif

import net.postchain.eif.contracts.TokenBridge.ExtraProofData
import net.postchain.eif.contracts.TokenBridge.Proof
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicBytes

data class WithdrawalEvent(
        var eventData: ByteArray? = null,
        var blockHeader: ByteArray? = null,
        var proof: Proof? = null,
        var signers: List<Address>? = null,
        var signatures: List<DynamicBytes>? = null,
        var extraProofData: ExtraProofData? = null
)
