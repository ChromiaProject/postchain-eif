package net.postchain.eif.transaction

import com.google.gson.Gson
import net.postchain.eif.Web3jRequestHandler
import net.postchain.gtv.Gtv
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Type
import java.util.*

class TransactionSubmitter(private val web3jRequestHandler: Web3jRequestHandler) {

    fun sendTransaction(contractAddress: String, functionName: String, parameterTypes: List<String>, parameterValues: List<Gtv>) {



//
//        val function = Function(
//            functionName,
//            Arrays.asList<Type<*>>(token, amount, ft3_account_id),
//            emptyList<TypeReference<*>>()
//        )
//        return executeRemoteCallTransaction(function)
//
//        web3jRequestHandler.sendWeb3jRequest { it. }
//
//        FunctionEncoder.makeFunction()
    }
}