# Copyright (c) 2020 and later Unicode, Inc. and others. All Rights Reserved.
FROM alpine as cbuild
WORKDIR /build
RUN apk add --update wget make gcc musl-dev
ARG CPATH=https://www.unicode.org/Public/PROGRAMS/BidiReferenceC/
# FIX_FOR_NEW_VERSION
ARG CVERSION=16.0.0
RUN wget -np -nv --reject-regex='.*\.(lib|exe)$' --cut-dirs=4 -nH -r ${CPATH}${CVERSION}/
RUN cd source && gcc -I ../include/ -static -Os -o3 -o bidiref1 bidiref1.c brutils.c brtest.c brtable.c brrule.c
RUN ls -lh /build/source/bidiref1 && (/build/source/bidiref1 || true)
# copy and unpack to /tmp/data
ADD ./target/cldr-unicodetools.tgz /build/data/
ADD ./target/generated.tgz /build/data/
# move this into place (including unicodetools/unicodetools)
RUN rm -rf /build/data/cldr/.git  # unneeded
FROM jetty:9-jre11-alpine-eclipse-temurin AS run
ADD port-entrypoint.sh /port-entrypoint.sh
ADD ./jetty.d/ROOT /var/lib/jetty/webapps/ROOT/
ENTRYPOINT [ "/port-entrypoint.sh" ]
# copy the .war
ADD ./target/UnicodeJsps.war /var/lib/jetty/webapps/
# copy the UCD for bidiref1
COPY src/main/resources/org/unicode/jsp/bidiref1/ucd/ /usr/local/share/ucd/
# this is the parent to 'ucd'
ENV BIDIREFHOME /usr/local/share
# copy the bidiref1 bin
ENV BIDIREF1 /usr/local/bin/bidiref1
COPY --from=cbuild /build/source/bidiref1 /usr/local/bin/
RUN mkdir -p /var/lib/jetty/data/unicodetools/Generated
COPY --from=cbuild /build/data/cldr /var/lib/jetty/data/cldr
COPY --from=cbuild /build/data/unicodetools/ /var/lib/jetty/data/unicodetools/unicodetools/
COPY --from=cbuild /build/data/Generated /var/lib/jetty/data/unicodetools/Generated
ENV JAVA_OPTIONS -DCLDR_DIR=/var/lib/jetty/data/cldr -DUNICODETOOLS_REPO_DIR=/var/lib/jetty/data/unicodetools -DUNICODETOOLS_GEN_DIR=/var/lib/jetty/data/unicodetools/Generated -Xmx4g
# This is the default PORT. Override by setting PORT.
EXPOSE 8080
