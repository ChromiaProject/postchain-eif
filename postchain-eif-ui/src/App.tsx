import { Web3ReactProvider } from "@web3-react/core";
import React, { useState } from "react";
import Connector, { getLibrary } from "./components/Connector";
import { Toaster } from "react-hot-toast";
import { QueryClient, QueryClientProvider } from "react-query";
import { ReactQueryDevtools } from "react-query/devtools";
import Bridge from "./Bridge";
import "./App.css";

const bridgeAddress = process.env.REACT_APP_TOKEN_BRIDGE_ADDRESS

const queryClient = new QueryClient();
function App() {
    const [tokenAddress, setTokenAddress] = useState("");

    function handleChange(event) {
        setTokenAddress(event.target.value);
    }

    return (
        <Web3ReactProvider getLibrary={getLibrary}>
            <QueryClientProvider client={queryClient}>
            <div className="App">
                <Connector />

                <select className="select select-bordered w-full max-w-xs" onChange={handleChange} defaultValue="">
                    <option value="">Please choose your token to deposit</option>
                    <option value="0x39615b16b74589919c9ce1ea73F1FC5D53141a78">CHR</option>
                    <option value="0x5C221E77624690fff6dd741493D735a17716c26B">DAI</option> 
                    <option value="0xd35CCeEAD182dcee0F148EbaC9447DA2c4D449c4">USDC</option>
                    <option value="0x63bfb2118771bd0da7A6936667A7BB705A06c1bA">LINK</option>
                    <option value="0x064e16771A4864561f767e4Ef4a6989fc4045aE7">ZKNFT</option>
                </select>
                {!!tokenAddress && !!bridgeAddress && (<Bridge bridgeAddress={bridgeAddress} tokenAddress={tokenAddress} />)}
            </div>
            <ReactQueryDevtools initialIsOpen={false} />
            <Toaster position="top-right" />            
            </QueryClientProvider>
        </Web3ReactProvider>
    );
}

export default App;