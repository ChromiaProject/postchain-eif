
# Must be >0.4.3
FROM registry.gitlab.com/chromaway/core-tools/chromia-cli/chr:latest
COPY postchain-eif-core/target/postchain-eif-core-dev.jar /usr/share/chr/lib
COPY postchain-eif-core/target/lib/* /usr/share/chr/lib
