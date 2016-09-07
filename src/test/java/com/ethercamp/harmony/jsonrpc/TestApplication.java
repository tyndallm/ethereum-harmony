/*
 * Copyright 2015, 2016 Ether.Camp Inc. (US)
 * This file is part of Ethereum Harmony.
 *
 * Ethereum Harmony is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Ethereum Harmony is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Ethereum Harmony.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ethercamp.harmony.jsonrpc;

import com.ethercamp.harmony.Application;
import com.ethercamp.harmony.jsontestsuite.suite.BlockTestCase;
import com.ethercamp.harmony.jsontestsuite.suite.BlockTestSuite;
import com.ethercamp.harmony.jsontestsuite.suite.JSONReader;
import com.ethercamp.harmony.jsontestsuite.suite.builder.BlockBuilder;
import com.ethercamp.harmony.jsontestsuite.suite.model.BlockTck;
import org.ethereum.config.DefaultConfig;
import org.ethereum.config.SystemProperties;
import org.ethereum.config.blockchain.FrontierConfig;
import org.ethereum.config.net.AbstractNetConfig;
import org.ethereum.core.Block;
import org.ethereum.core.BlockchainImpl;
import org.ethereum.core.ImportResult;
import org.ethereum.core.Transaction;
import org.ethereum.crypto.ECKey;
import org.ethereum.facade.EthereumImpl;
import org.spongycastle.util.encoders.Hex;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;


/**
 * Class which runs a node with the predefined blockchain for testing with JSON-RPC
 * test suite: https://github.com/ethereum/rpc-tests
 *
 *  git clone https://github.com/ethereum/rpc-tests
 *  cd rpc-tests
 *  git submodule update --init
 *  npm install
 *  make test
 *  sudo npm install -g mocha
 *  # edit lib/config.js: comment all hosts and IPC, add your own host
 *  rm test/1_testConnection.js # jsonrpc4j don't like simple connection attempt
 *  mocha -d test/
 *  mocha -d test/<test>.js
 */
@SpringBootApplication
@EnableScheduling
@Import(DefaultConfig.class)
public class TestApplication {

    /**
     * bcRPC_API_Test.json at older revision requires older-style Frontier
     * signature verification (outdated test)
     */
    public static class OldFrontierBCConfig extends AbstractNetConfig {
        public OldFrontierBCConfig() {
            add(0, new FrontierConfig() {
                @Override
                public boolean acceptTransactionSignature(Transaction tx) {
                    return true;
                }
            });
        }
    }

    public static void main(String[] args) throws IOException {
        SystemProperties.getDefault().setBlockchainConfig(new OldFrontierBCConfig());
        // rpc.json genesis created from bcRPC_API_Test.json
        SystemProperties.getDefault().overrideParams(
                "genesis", "rpc.json",
                "database.dir", "no-dir");

        ConfigurableApplicationContext context =
                SpringApplication.run(new Object[]{DefaultConfig.class, Application.class}, args);
        processAfterContext(context);
    }

    public static void processAfterContext(ApplicationContext context) throws IOException {
        EthereumImpl ethereum = context.getBean(EthereumImpl.class);
        String json = JSONReader.loadJSONFromCommit("BlockchainTests/bcRPC_API_Test.json", "c58d3dce3f64f7ee1f711054fc464202bb0b7d64");
        BlockTestSuite testSuite = new BlockTestSuite(json);
        BlockTestCase aCase = testSuite.getTestCases().get("RPC_API_Test");
        ((BlockchainImpl) ethereum.getBlockchain()).byTest = true;

        for (BlockTck blockTck : aCase.getBlocks()) {
            Block block = BlockBuilder.build(blockTck.getBlockHeader(),
                    blockTck.getTransactions(),
                    blockTck.getUncleHeaders());
            ImportResult result = ((BlockchainImpl) ethereum.getBlockchain()).tryToConnect(block);
            if (result != ImportResult.IMPORTED_BEST && result != ImportResult.IMPORTED_NOT_BEST) {
                throw new RuntimeException("Can't import test block: " + result + "\n" + block);
            }
        }

        // need a predefined coinbase account
        JsonRpcImpl rpcService = context.getBean(JsonRpcImpl.class);

        ECKey ecKey = ECKey.fromPrivate(Hex.decode("45a915e4d060149eb4365960e6a7a45f334393093061116b197e3240065ff2d8"));
        String rawKey = Hex.toHexString(ecKey.getPrivKeyBytes());
        String address = "0x" + Hex.toHexString(ecKey.getAddress());

        rpcService.keystore.removeKey(Hex.toHexString(ecKey.getAddress()));
        rpcService.personal_importRawKey(rawKey, "123");
        rpcService.personal_unlockAccount(address, "123", null);
    }
}
