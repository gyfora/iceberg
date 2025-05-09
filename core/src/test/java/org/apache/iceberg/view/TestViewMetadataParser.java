/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.view;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableMetadataParser.Codec;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class TestViewMetadataParser {

  private static final Schema TEST_SCHEMA =
      new Schema(
          Types.NestedField.required(1, "x", Types.LongType.get()),
          Types.NestedField.required(2, "y", Types.LongType.get(), "comment"),
          Types.NestedField.required(3, "z", Types.LongType.get()));

  @TempDir private Path tmp;

  @Test
  public void nullAndEmptyCheck() {
    assertThatThrownBy(() -> ViewMetadataParser.fromJson((String) null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Cannot parse view metadata from null string");

    assertThatThrownBy(() -> ViewMetadataParser.fromJson((JsonNode) null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Cannot parse view metadata from null object");

    assertThatThrownBy(() -> ViewMetadataParser.toJson(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid view metadata: null");
  }

  @Test
  public void readAndWriteValidViewMetadata() throws Exception {
    ViewVersion version1 =
        ImmutableViewVersion.builder()
            .versionId(1)
            .timestampMillis(4353L)
            .summary(ImmutableMap.of("user", "some-user"))
            .schemaId(0)
            .defaultCatalog("some-catalog")
            .defaultNamespace(Namespace.empty())
            .addRepresentations(
                ImmutableSQLViewRepresentation.builder()
                    .sql("select 'foo' foo")
                    .dialect("spark-sql")
                    .build())
            .build();

    ViewVersion version2 =
        ImmutableViewVersion.builder()
            .versionId(2)
            .schemaId(0)
            .timestampMillis(5555L)
            .summary(ImmutableMap.of("user", "some-user"))
            .defaultCatalog("some-catalog")
            .defaultNamespace(Namespace.empty())
            .addRepresentations(
                ImmutableSQLViewRepresentation.builder()
                    .sql("select 1 id, 'abc' data")
                    .dialect("spark-sql")
                    .build())
            .build();

    String json = readViewMetadataInputFile("org/apache/iceberg/view/ValidViewMetadata.json");
    ViewMetadata expectedViewMetadata =
        ViewMetadata.buildFrom(
                ViewMetadata.builder()
                    .assignUUID("fa6506c3-7681-40c8-86dc-e36561f83385")
                    .addSchema(TEST_SCHEMA)
                    .addVersion(version1)
                    .setLocation("s3://bucket/test/location")
                    .setProperties(
                        ImmutableMap.of(
                            "some-key", "some-value", ViewProperties.COMMENT, "some-comment"))
                    .setCurrentVersionId(1)
                    .upgradeFormatVersion(1)
                    .build())
            .addVersion(version2)
            .setCurrentVersionId(2)
            .build();

    ViewMetadata actual = ViewMetadataParser.fromJson(json);
    assertThat(actual)
        .usingRecursiveComparison()
        .ignoringFieldsOfTypes(Schema.class)
        .ignoringFields("changes")
        .isEqualTo(expectedViewMetadata);
    for (Schema schema : expectedViewMetadata.schemas()) {
      assertThat(schema.sameSchema(actual.schemasById().get(schema.schemaId()))).isTrue();
    }

    actual = ViewMetadataParser.fromJson(ViewMetadataParser.toJson(expectedViewMetadata));
    assertThat(actual)
        .usingRecursiveComparison()
        .ignoringFieldsOfTypes(Schema.class)
        .ignoringFields("changes")
        .isEqualTo(expectedViewMetadata);
    for (Schema schema : expectedViewMetadata.schemas()) {
      assertThat(schema.sameSchema(actual.schemasById().get(schema.schemaId()))).isTrue();
    }
  }

  @Test
  public void failReadingViewMetadataMissingLocation() throws Exception {
    String json =
        readViewMetadataInputFile("org/apache/iceberg/view/ViewMetadataMissingLocation.json");
    assertThatThrownBy(() -> ViewMetadataParser.fromJson(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Cannot parse missing string: location");
  }

  @Test
  public void failReadingViewMetadataInvalidSchemaId() throws Exception {
    String json =
        readViewMetadataInputFile("org/apache/iceberg/view/ViewMetadataInvalidCurrentSchema.json");
    ViewMetadata metadata = ViewMetadataParser.fromJson(json);
    assertThatThrownBy(metadata::currentSchemaId)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Cannot find current schema with id 1234 in schemas: [1]");
  }

  @Test
  public void failReadingViewMetadataMissingVersion() throws Exception {
    String json =
        readViewMetadataInputFile("org/apache/iceberg/view/ViewMetadataMissingCurrentVersion.json");
    assertThatThrownBy(() -> ViewMetadataParser.fromJson(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Cannot parse missing int: current-version-id");
  }

  @Test
  public void failReadingViewMetadataInvalidVersionId() throws Exception {
    String json =
        readViewMetadataInputFile("org/apache/iceberg/view/ViewMetadataInvalidCurrentVersion.json");
    ViewMetadata metadata = ViewMetadataParser.fromJson(json);
    assertThatThrownBy(metadata::currentVersion)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Cannot find current version 1234 in view versions: [1, 2]");
  }

  private String readViewMetadataInputFile(String fileName) throws Exception {
    Path path = Paths.get(getClass().getClassLoader().getResource(fileName).toURI());
    return String.join("", java.nio.file.Files.readAllLines(path));
  }

  @Test
  public void viewMetadataWithMetadataLocation() throws Exception {
    ViewVersion version1 =
        ImmutableViewVersion.builder()
            .versionId(1)
            .timestampMillis(4353L)
            .summary(ImmutableMap.of("user", "some-user"))
            .schemaId(0)
            .defaultCatalog("some-catalog")
            .defaultNamespace(Namespace.empty())
            .addRepresentations(
                ImmutableSQLViewRepresentation.builder()
                    .sql("select 'foo' foo")
                    .dialect("spark-sql")
                    .build())
            .build();

    ViewVersion version2 =
        ImmutableViewVersion.builder()
            .versionId(2)
            .schemaId(0)
            .timestampMillis(5555L)
            .summary(ImmutableMap.of("user", "some-user"))
            .defaultCatalog("some-catalog")
            .defaultNamespace(Namespace.empty())
            .addRepresentations(
                ImmutableSQLViewRepresentation.builder()
                    .sql("select 1 id, 'abc' data")
                    .dialect("spark-sql")
                    .build())
            .build();

    String json = readViewMetadataInputFile("org/apache/iceberg/view/ValidViewMetadata.json");
    String metadataLocation = "s3://bucket/test/location/metadata/v1.metadata.json";
    ViewMetadata expectedViewMetadata =
        ViewMetadata.buildFrom(
                ViewMetadata.buildFrom(
                        ViewMetadata.builder()
                            .assignUUID("fa6506c3-7681-40c8-86dc-e36561f83385")
                            .addSchema(TEST_SCHEMA)
                            .addVersion(version1)
                            .setLocation("s3://bucket/test/location")
                            .setProperties(
                                ImmutableMap.of(
                                    "some-key",
                                    "some-value",
                                    ViewProperties.COMMENT,
                                    "some-comment"))
                            .setCurrentVersionId(1)
                            .upgradeFormatVersion(1)
                            .build())
                    .addVersion(version2)
                    .setCurrentVersionId(2)
                    .build())
            .setMetadataLocation(metadataLocation)
            .build();

    ViewMetadata actual = ViewMetadataParser.fromJson(metadataLocation, json);
    assertThat(actual)
        .usingRecursiveComparison()
        .ignoringFieldsOfTypes(Schema.class)
        .isEqualTo(expectedViewMetadata);

    actual =
        ViewMetadataParser.fromJson(
            metadataLocation, ViewMetadataParser.toJson(expectedViewMetadata));
    assertThat(actual)
        .usingRecursiveComparison()
        .ignoringFieldsOfTypes(Schema.class)
        .isEqualTo(expectedViewMetadata);
    assertThat(actual.metadataFileLocation()).isEqualTo(metadataLocation);
  }

  @Test
  public void viewMetadataWithMultipleSQLsForDialectShouldBeReadable() throws Exception {
    ViewVersion viewVersion =
        ImmutableViewVersion.builder()
            .versionId(1)
            .timestampMillis(4353L)
            .summary(ImmutableMap.of("user", "some-user"))
            .schemaId(0)
            .defaultCatalog("some-catalog")
            .defaultNamespace(Namespace.empty())
            .addRepresentations(
                ImmutableSQLViewRepresentation.builder()
                    .sql("select 'foo' foo")
                    .dialect("spark-sql")
                    .build())
            .addRepresentations(
                ImmutableSQLViewRepresentation.builder()
                    .sql("select * from foo")
                    .dialect("spark-sql")
                    .build())
            .build();

    String json =
        readViewMetadataInputFile(
            "org/apache/iceberg/view/ViewMetadataMultipleSQLsForDialect.json");

    // builder will throw an exception due to having multiple SQLs for the same dialect, thus
    // construct the expected view metadata directly
    ViewMetadata expectedViewMetadata =
        ImmutableViewMetadata.of(
            "fa6506c3-7681-40c8-86dc-e36561f83385",
            1,
            "s3://bucket/test/location",
            ImmutableList.of(TEST_SCHEMA),
            1,
            ImmutableList.of(viewVersion),
            ImmutableList.of(
                ImmutableViewHistoryEntry.builder().versionId(1).timestampMillis(4353).build()),
            ImmutableMap.of("some-key", "some-value"),
            ImmutableList.of(),
            null);

    // reading view metadata with multiple SQLs for the same dialects shouldn't fail
    ViewMetadata actual = ViewMetadataParser.fromJson(json);
    assertThat(actual)
        .usingRecursiveComparison()
        .ignoringFieldsOfTypes(Schema.class)
        .isEqualTo(expectedViewMetadata);
  }

  @Test
  public void replaceViewMetadataWithMultipleSQLsForDialect() throws Exception {
    String json =
        readViewMetadataInputFile(
            "org/apache/iceberg/view/ViewMetadataMultipleSQLsForDialect.json");

    // reading view metadata with multiple SQLs for the same dialects shouldn't fail
    ViewMetadata invalid = ViewMetadataParser.fromJson(json);

    // replace metadata with a new view version that fixes the SQL representations
    ViewVersion viewVersion =
        ImmutableViewVersion.builder()
            .versionId(2)
            .schemaId(0)
            .timestampMillis(5555L)
            .summary(ImmutableMap.of("user", "some-user"))
            .defaultCatalog("some-catalog")
            .defaultNamespace(Namespace.empty())
            .addRepresentations(
                ImmutableSQLViewRepresentation.builder()
                    .sql("select * from foo")
                    .dialect("spark-sql")
                    .build())
            .build();

    ViewMetadata replaced =
        ViewMetadata.buildFrom(invalid).addVersion(viewVersion).setCurrentVersionId(2).build();

    assertThat(replaced.currentVersion()).isEqualTo(viewVersion);
  }

  @ParameterizedTest
  @ValueSource(strings = {"v1.metadata.json", "v1.gz.metadata.json"})
  public void metadataCompression(String fileName) throws IOException {
    Codec codec = fileName.startsWith("v1.gz") ? Codec.GZIP : Codec.NONE;
    String location = Paths.get(tmp.toString(), fileName).toString();
    OutputFile outputFile = org.apache.iceberg.Files.localOutput(location);

    Schema schema = new Schema(Types.NestedField.required(1, "x", Types.LongType.get()));
    ViewVersion viewVersion =
        ImmutableViewVersion.builder()
            .schemaId(0)
            .versionId(1)
            .timestampMillis(23L)
            .putSummary("user", "some-user")
            .defaultNamespace(Namespace.of("ns"))
            .build();

    ViewMetadata metadata =
        ViewMetadata.buildFrom(
                ViewMetadata.builder()
                    .setLocation(location)
                    .addSchema(schema)
                    .setProperties(
                        ImmutableMap.of(ViewProperties.METADATA_COMPRESSION, codec.name()))
                    .addVersion(viewVersion)
                    .setCurrentVersionId(1)
                    .build())
            .setMetadataLocation(outputFile.location())
            .build();

    ViewMetadataParser.write(metadata, outputFile);
    assertThat(Codec.GZIP == codec).isEqualTo(isCompressed(location));

    ViewMetadata actualMetadata =
        ViewMetadataParser.read(org.apache.iceberg.Files.localInput(location));

    assertThat(actualMetadata)
        .usingRecursiveComparison()
        .ignoringFieldsOfTypes(Schema.class)
        .isEqualTo(metadata);
  }

  private boolean isCompressed(String path) throws IOException {
    try (InputStream ignored = new GZIPInputStream(Files.newInputStream(new File(path).toPath()))) {
      return true;
    } catch (ZipException e) {
      if (e.getMessage().equals("Not in GZIP format")) {
        return false;
      } else {
        throw e;
      }
    }
  }

  @Test
  public void roundTripSerdeWithoutProperties() {
    String uuid = "386b9f01-002b-4d8c-b77f-42c3fd3b7c9b";
    ViewMetadata viewMetadata =
        ViewMetadata.builder()
            .assignUUID(uuid)
            .setLocation("location")
            .addSchema(new Schema(Types.NestedField.required(1, "x", Types.LongType.get())))
            .addVersion(
                ImmutableViewVersion.builder()
                    .schemaId(0)
                    .versionId(1)
                    .timestampMillis(23L)
                    .putSummary("operation", "create")
                    .defaultNamespace(Namespace.of("ns1"))
                    .build())
            .setCurrentVersionId(1)
            .build();

    String expectedJson =
        "{\n"
            + "  \"view-uuid\" : \"386b9f01-002b-4d8c-b77f-42c3fd3b7c9b\",\n"
            + "  \"format-version\" : 1,\n"
            + "  \"location\" : \"location\",\n"
            + "  \"schemas\" : [ {\n"
            + "    \"type\" : \"struct\",\n"
            + "    \"schema-id\" : 0,\n"
            + "    \"fields\" : [ {\n"
            + "      \"id\" : 1,\n"
            + "      \"name\" : \"x\",\n"
            + "      \"required\" : true,\n"
            + "      \"type\" : \"long\"\n"
            + "    } ]\n"
            + "  } ],\n"
            + "  \"current-version-id\" : 1,\n"
            + "  \"versions\" : [ {\n"
            + "    \"version-id\" : 1,\n"
            + "    \"timestamp-ms\" : 23,\n"
            + "    \"schema-id\" : 0,\n"
            + "    \"summary\" : {\n"
            + "      \"operation\" : \"create\"\n"
            + "    },\n"
            + "    \"default-namespace\" : [ \"ns1\" ],\n"
            + "    \"representations\" : [ ]\n"
            + "  } ],\n"
            + "  \"version-log\" : [ {\n"
            + "    \"timestamp-ms\" : 23,\n"
            + "    \"version-id\" : 1\n"
            + "  } ]\n"
            + "}";

    String json = ViewMetadataParser.toJson(viewMetadata, true);
    assertThat(json).isEqualTo(expectedJson);

    assertThat(ViewMetadataParser.fromJson(json))
        .usingRecursiveComparison()
        .ignoringFieldsOfTypes(Schema.class)
        .ignoringFields("changes")
        .isEqualTo(viewMetadata);
  }
}
