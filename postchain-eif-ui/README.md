# EIF dapp sample

### Run simple dapp on local

Create `.env` file to config your dapp, then run below command to start the dapp on local

```
$ yarn start
```
Voila! Now you can access to `http://localhost:3000/` to use the dapp to interact with token bridge smart contract

### Build the DApp for production deployment

```
$ yarn install && yarn build

```

The build folder is ready to be deployed.
We may serve it with a static server:

```
$ yarn global add serve
$ serve -s build
```

Find out more about deployment here:
    https://cra.link/deployment
