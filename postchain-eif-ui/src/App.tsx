import { Web3ReactProvider } from "@web3-react/core";
import React, { useState } from "react";
import Connector, { getLibrary } from "./components/Connector";
import { Toaster } from "react-hot-toast";
import { QueryClient, QueryClientProvider } from "react-query";
import { ReactQueryDevtools } from "react-query/devtools";
import Bridge from "./Bridge";
import "./App.css";

const goerliBridgeAddress = process.env.REACT_APP_GOERLI_TOKEN_BRIDGE_ADDRESS
const bscBridgeAddress = process.env.REACT_APP_BSC_TOKEN_BRIDGE_ADDRESS
const mumbaiBridgeAddress = process.env.REACT_APP_MUMBAI_TOKEN_BRIDGE_ADDRESS
const sepoliaBridgeAddress = process.env.REACT_APP_SEPOLIA_TOKEN_BRIDGE_ADDRESS

const queryClient = new QueryClient();

function App() {
    const [chainId, setChainId] = useState(5)
    const [tokenAddress, setTokenAddress] = useState("")

    function handleChange(event) {
        setTokenAddress(event.target.value)
    }

    function handleChangeNetwork(event) {
        setChainId(event.target.value)
    }

    return (
        <Web3ReactProvider getLibrary={getLibrary}>
            <QueryClientProvider client={queryClient}>
            <div className="App">
                <Connector />
                <div class="form-control">
                    <label class="label">
                        <span class="label-text">Pick the network</span>
                    </label>
                    <select id="network" className="select select-bordered w-full max-w-xs" onChange={handleChangeNetwork} defaultValue="5">
                        <option value="5">5 - Goerli test network</option>
                        <option value="97">97 - BNB Smart Chain Testnet</option>
                        <option value="80001">80001 - Polygon Mumbai</option>
                        <option value="11155111">11155111 - Sepolia test network</option>
                    </select>
                    <label class="label">
                        <span class="label-text">Pick the token</span>
                    </label>
                    <select id="token" className="select select-bordered w-full max-w-xs" onChange={handleChange} defaultValue="">
                        <option value="">Please choose your token to deposit</option>
                        <option value="0x39615b16b74589919c9ce1ea73F1FC5D53141a78">CHR (Goerli)</option>
                        <option value="0x5C221E77624690fff6dd741493D735a17716c26B">DAI (Goerli)</option>
                        <option value="0xd35CCeEAD182dcee0F148EbaC9447DA2c4D449c4">USDC (Goerli)</option>
                        <option value="0x63bfb2118771bd0da7A6936667A7BB705A06c1bA">LINK (Goerli)</option>
                        <option value="0x932Ca55B9Ef0b3094E8Fa82435b3b4c50d713043">G_NFTS (Goerli)</option>
                        <option value="0xeD24FC36d5Ee211Ea25A80239Fb8C4Cfd80f12Ee">BUSD (BSC Testnet)</option>
                        <option value="0x84b9B910527Ad5C03A9Ca831909E21e236EA7b06">LINK (BSC Testnet)</option>
                        <option value="0x326C977E6efc84E512bB9C30f76E30c160eD06FB">LINK (Mumbai)</option>
                        <option value="0xfe4F5145f6e09952a5ba9e956ED0C25e3Fa4c7F1">DERC20 (Mumbai)</option>
                    </select>
                </div>
                {chainId == 5 && !!tokenAddress && !!goerliBridgeAddress && (<Bridge bridgeAddress={goerliBridgeAddress} tokenAddress={tokenAddress} />)}
                {chainId == 97 && !!tokenAddress && !!bscBridgeAddress && (<Bridge bridgeAddress={bscBridgeAddress} tokenAddress={tokenAddress} />)}
                {chainId == 80001 && !!tokenAddress && !!mumbaiBridgeAddress && (<Bridge bridgeAddress={mumbaiBridgeAddress} tokenAddress={tokenAddress} />)}
                {chainId == 11155111 && !!tokenAddress && !!sepoliaBridgeAddress && (<Bridge bridgeAddress={sepoliaBridgeAddress} tokenAddress={tokenAddress} />)}
            </div>
            <ReactQueryDevtools initialIsOpen={false} />
            <Toaster position="top-right" />            
            </QueryClientProvider>
        </Web3ReactProvider>
    );
}

export default App;