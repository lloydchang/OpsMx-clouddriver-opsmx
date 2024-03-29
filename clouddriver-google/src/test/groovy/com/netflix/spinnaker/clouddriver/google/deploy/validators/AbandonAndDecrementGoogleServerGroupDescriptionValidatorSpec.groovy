/*
 * Copyright 2015 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.google.deploy.validators

import com.netflix.spinnaker.clouddriver.deploy.ValidationErrors
import com.netflix.spinnaker.clouddriver.google.deploy.description.AbandonAndDecrementGoogleServerGroupDescription
import com.netflix.spinnaker.clouddriver.google.security.FakeGoogleCredentials
import com.netflix.spinnaker.clouddriver.google.security.GoogleNamedAccountCredentials
import com.netflix.spinnaker.clouddriver.google.security.TestDefaults
import com.netflix.spinnaker.clouddriver.security.DefaultAccountCredentialsProvider
import com.netflix.spinnaker.clouddriver.security.MapBackedAccountCredentialsRepository
import com.netflix.spinnaker.credentials.MapBackedCredentialsRepository
import com.netflix.spinnaker.credentials.NoopCredentialsLifecycleHandler
import spock.lang.Shared
import spock.lang.Specification

class AbandonAndDecrementGoogleServerGroupDescriptionValidatorSpec extends Specification implements TestDefaults {
  private static final SERVER_GROUP_NAME = "server-group-name"
  private static final ACCOUNT_NAME = "auto"
  private static final REGION = "us-central1"
  private static final INSTANCE_IDS = ["my-app7-dev-v000-instance1", "my-app7-dev-v000-instance2"]

  @Shared
  AbandonAndDecrementGoogleServerGroupDescriptionValidator validator

  @Shared
  GoogleNamedAccountCredentials credentials

  void setupSpec() {
    validator = new AbandonAndDecrementGoogleServerGroupDescriptionValidator()
    def credentialsRepo = new MapBackedCredentialsRepository(GoogleNamedAccountCredentials.CREDENTIALS_TYPE,
      new NoopCredentialsLifecycleHandler<>())
    credentials =
      new GoogleNamedAccountCredentials.Builder()
          .name(ACCOUNT_NAME)
          .credentials(new FakeGoogleCredentials())
          .regionToZonesMap(REGION_TO_ZONES)
          .build()
    credentialsRepo.save(credentials)
    validator.credentialsRepository = credentialsRepo
  }

  void "pass validation with proper description inputs"() {
    setup:
      def description =
        new AbandonAndDecrementGoogleServerGroupDescription(region: REGION,
                                                            serverGroupName: SERVER_GROUP_NAME,
                                                            instanceIds: INSTANCE_IDS,
                                                            accountName: ACCOUNT_NAME,
                                                            credentials: credentials)
      def errors = Mock(ValidationErrors)

    when:
      validator.validate([], description, errors)

    then:
      0 * errors._
  }

  void "invalid instanceIds fail validation"() {
    setup:
      def description = new AbandonAndDecrementGoogleServerGroupDescription(instanceIds: [""], serverGroupName: SERVER_GROUP_NAME)
      def errors = Mock(ValidationErrors)

    when:
      validator.validate([], description, errors)

    then:
      1 * errors.rejectValue("instanceIds", "abandonAndDecrementGoogleServerGroupDescription.instanceId0.empty")
  }

  void "null input fails validation"() {
    setup:
      def description = new AbandonAndDecrementGoogleServerGroupDescription()
      def errors = Mock(ValidationErrors)

    when:
      validator.validate([], description, errors)

    then:
      1 * errors.rejectValue('credentials', _)
      1 * errors.rejectValue('region', _)
      1 * errors.rejectValue('serverGroupName', _)
      1 * errors.rejectValue('instanceIds', _)
  }
}
