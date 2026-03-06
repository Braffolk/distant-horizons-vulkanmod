FROM eclipse-temurin:17-jdk

WORKDIR /home/build/
COPY ./gradlew .
RUN chmod +x ./gradlew
CMD echo "\r========== [CLEAN: $MC_VER] ==========" && \
    ./gradlew clean -PmcVer="$MC_VER" --gradle-user-home .gradle-cache/ && \
    echo "\r========== [BUILD: $MC_VER] ==========" && \
    ./gradlew build -PmcVer="$MC_VER" --gradle-user-home .gradle-cache/ && \
    echo "\r========== [MERGE: $MC_VER] ==========" && \
    ./gradlew mergeJars -PmcVer="$MC_VER" --gradle-user-home .gradle-cache/ && \
    echo "\r========== [DONE:  $MC_VER] =========="
