import { InjectedConnector } from "@web3-react/injected-connector";

export const POLLING_INTERVAL = 12000;
export const injected = new InjectedConnector({
  supportedChainIds: [5, 97, 80001, 11155111, 17000],
});