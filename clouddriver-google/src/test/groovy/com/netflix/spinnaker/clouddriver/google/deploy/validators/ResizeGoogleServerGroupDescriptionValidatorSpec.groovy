/*
 * Copyright 2014 Google, Inc.
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
import com.netflix.spinnaker.clouddriver.google.deploy.description.ResizeGoogleServerGroupDescription
import com.netflix.spinnaker.clouddriver.google.deploy.description.UpsertGoogleAutoscalingPolicyDescription
import com.netflix.spinnaker.clouddriver.google.security.FakeGoogleCredentials
import com.netflix.spinnaker.clouddriver.google.security.GoogleNamedAccountCredentials
import com.netflix.spinnaker.clouddriver.security.DefaultAccountCredentialsProvider
import com.netflix.spinnaker.clouddriver.security.MapBackedAccountCredentialsRepository
import com.netflix.spinnaker.credentials.MapBackedCredentialsRepository
import com.netflix.spinnaker.credentials.NoopCredentialsLifecycleHandler
import spock.lang.Shared
import spock.lang.Specification

class ResizeGoogleServerGroupDescriptionValidatorSpec extends Specification {
  private static final SERVER_GROUP_NAME = "spinnaker-test-v000"
  private static final TARGET_SIZE = 5
  private static final REGION = "us-central1"
  private static final ACCOUNT_NAME = "auto"

  @Shared
  ResizeGoogleServerGroupDescriptionValidator validator

  void setupSpec() {
    validator = new ResizeGoogleServerGroupDescriptionValidator()
    def credentialsRepo = new MapBackedCredentialsRepository(GoogleNamedAccountCredentials.CREDENTIALS_TYPE,
      new NoopCredentialsLifecycleHandler<>())
    def credentials = new GoogleNamedAccountCredentials.Builder().name(ACCOUNT_NAME).credentials(new FakeGoogleCredentials()).build()
    credentialsRepo.save(credentials)
    validator.credentialsRepository = credentialsRepo
  }

  void "pass validation with proper description inputs"() {
    setup:
      def description = new ResizeGoogleServerGroupDescription(serverGroupName: SERVER_GROUP_NAME,
                                                               targetSize: TARGET_SIZE,
                                                               region: REGION,
                                                               accountName: ACCOUNT_NAME)
      def errors = Mock(ValidationErrors)

    when:
      validator.validate([], description, errors)

    then:
      0 * errors._
  }

  void "invalid targetSize fails validation"() {
    setup:
      def description = new ResizeGoogleServerGroupDescription(targetSize: -1)
      def errors = Mock(ValidationErrors)

    when:
      validator.validate([], description, errors)

    then:
      1 * errors.rejectValue("targetSize", "resizeGoogleServerGroupDescription.targetSize.negative")
  }

  void "null input fails validation"() {
    setup:
      def description = new ResizeGoogleServerGroupDescription()
      def errors = Mock(ValidationErrors)

    when:
      validator.validate([], description, errors)

    then:
      1 * errors.rejectValue('credentials', _)
      1 * errors.rejectValue('serverGroupName', _)
      1 * errors.rejectValue('region', _)
  }

  void "uses capacity.desired instead of targetSize"() {
    setup: "missing either size specification defaults to zero"
      def description = new ResizeGoogleServerGroupDescription(
          serverGroupName: SERVER_GROUP_NAME,
          region: REGION,
          accountName: ACCOUNT_NAME
      )
      def errors = Mock(ValidationErrors)

    when:
      // no description.capacity set.
      validator.validate([], description, errors)

    then:
      1 * errors.rejectValue("targetSize", "resizeGoogleServerGroupDescription.targetSize.empty")

    when:
      description.capacity = new ResizeGoogleServerGroupDescription.Capacity(desired: 10)
      validator.validate([], description, errors)

    then:
      0 * errors._

    when:
      description.capacity = new ResizeGoogleServerGroupDescription.Capacity(desired: -10)
      validator.validate([], description, errors)

    then:
      1 * errors.rejectValue("targetSize", "resizeGoogleServerGroupDescription.targetSize.negative")

    when:
      description.capacity = new ResizeGoogleServerGroupDescription.Capacity()
      validator.validate([], description, errors)

    then:
      1 * errors.rejectValue("targetSize", "resizeGoogleServerGroupDescription.targetSize.empty")
  }

  void "UpsertGoogleAutoscalingPolicyDescription is validated by UpsertGoogleAutoscalingPolicyDescriptionValidator"() {
    setup:
      def description = new UpsertGoogleAutoscalingPolicyDescription(serverGroupName: SERVER_GROUP_NAME,
                                                                     region: REGION,
                                                                     accountName: ACCOUNT_NAME)
      def errors = Mock(ValidationErrors)

    when:
      validator.validate([], description, errors)

    then:
      1 * errors.rejectValue('autoscalingPolicy', 'upsertGoogleScalingPolicyDescription.autoscalingPolicy.empty')
  }
}
