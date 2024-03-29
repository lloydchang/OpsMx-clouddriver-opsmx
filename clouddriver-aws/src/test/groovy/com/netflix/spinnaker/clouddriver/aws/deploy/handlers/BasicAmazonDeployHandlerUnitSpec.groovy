/*
 * Copyright 2014 Netflix, Inc.
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

package com.netflix.spinnaker.clouddriver.aws.deploy.handlers

import com.amazonaws.services.autoscaling.AmazonAutoScaling
import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import com.amazonaws.services.autoscaling.model.BlockDeviceMapping
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsResult
import com.amazonaws.services.autoscaling.model.LaunchConfiguration
import com.amazonaws.services.autoscaling.model.LaunchTemplate
import com.amazonaws.services.autoscaling.model.LaunchTemplateOverrides
import com.amazonaws.services.autoscaling.model.LaunchTemplateSpecification
import com.amazonaws.services.autoscaling.model.MixedInstancesPolicy
import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.ec2.model.DescribeImagesRequest
import com.amazonaws.services.ec2.model.DescribeImagesResult
import com.amazonaws.services.ec2.model.DescribeInstanceTypesResult
import com.amazonaws.services.ec2.model.DescribeVpcClassicLinkResult
import com.amazonaws.services.ec2.model.EbsBlockDevice
import com.amazonaws.services.ec2.model.Image
import com.amazonaws.services.ec2.model.InstanceTypeInfo
import com.amazonaws.services.ec2.model.LaunchTemplateBlockDeviceMapping
import com.amazonaws.services.ec2.model.LaunchTemplateVersion
import com.amazonaws.services.ec2.model.ProcessorInfo
import com.amazonaws.services.ec2.model.ResponseLaunchTemplateData
import com.amazonaws.services.ec2.model.VpcClassicLink
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancing as AmazonELBV1
import com.amazonaws.services.elasticloadbalancing.model.DescribeLoadBalancersResult
import com.amazonaws.services.elasticloadbalancing.model.LoadBalancerDescription
import com.amazonaws.services.elasticloadbalancing.model.LoadBalancerNotFoundException as LBNFEV1
import com.amazonaws.services.elasticloadbalancingv2.AmazonElasticLoadBalancing
import com.amazonaws.services.elasticloadbalancingv2.model.DescribeTargetGroupsRequest
import com.amazonaws.services.elasticloadbalancingv2.model.DescribeTargetGroupsResult
import com.amazonaws.services.elasticloadbalancingv2.model.TargetGroup
import com.netflix.spinnaker.clouddriver.aws.deploy.asg.LaunchTemplateRollOutConfig
import com.netflix.spinnaker.clouddriver.aws.services.LaunchTemplateService
import com.netflix.spinnaker.clouddriver.deploy.DeploymentResult
import com.netflix.spinnaker.config.AwsConfiguration
import com.netflix.spinnaker.config.AwsConfiguration.DeployDefaults
import com.netflix.spinnaker.clouddriver.aws.TestCredential
import com.netflix.spinnaker.clouddriver.aws.deploy.asg.AsgReferenceCopier
import com.netflix.spinnaker.clouddriver.aws.deploy.asg.AutoScalingWorker
import com.netflix.spinnaker.clouddriver.aws.deploy.asg.AutoScalingWorker.AsgConfiguration
import com.netflix.spinnaker.clouddriver.aws.deploy.InstanceTypeUtils
import com.netflix.spinnaker.clouddriver.aws.deploy.InstanceTypeUtils.BlockDeviceConfig
import com.netflix.spinnaker.clouddriver.aws.deploy.description.BasicAmazonDeployDescription
import com.netflix.spinnaker.clouddriver.aws.deploy.ops.loadbalancer.LoadBalancerLookupHelper
import com.netflix.spinnaker.clouddriver.aws.deploy.ops.loadbalancer.UpsertAmazonLoadBalancerResult
import com.netflix.spinnaker.clouddriver.aws.deploy.scalingpolicy.ScalingPolicyCopier
import com.netflix.spinnaker.clouddriver.aws.model.AmazonAsgLifecycleHook
import com.netflix.spinnaker.clouddriver.aws.model.AmazonBlockDevice
import com.netflix.spinnaker.clouddriver.aws.model.AmazonServerGroup
import com.netflix.spinnaker.clouddriver.aws.security.AmazonCredentials
import com.netflix.spinnaker.clouddriver.aws.security.NetflixAmazonCredentials
import com.netflix.spinnaker.clouddriver.aws.services.AsgService
import com.netflix.spinnaker.clouddriver.aws.services.RegionScopedProviderFactory
import com.netflix.spinnaker.clouddriver.aws.services.RegionScopedProviderFactory.RegionScopedProvider
import com.netflix.spinnaker.clouddriver.data.task.Task
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository
import com.netflix.spinnaker.credentials.CredentialsRepository
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

import static BasicAmazonDeployHandler.SUBNET_ID_OVERRIDE_TAG

class BasicAmazonDeployHandlerUnitSpec extends Specification {

  @Subject
  BasicAmazonDeployHandler handler

  @Shared
  NetflixAmazonCredentials testCredentials = TestCredential.named('test')

  @Shared
  DeployDefaults deployDefaults = new DeployDefaults(
    unknownInstanceTypeBlockDevice: new AmazonBlockDevice(deviceName: "/dev/sdb", size: 40)
  )

  @Shared
  BlockDeviceConfig blockDeviceConfig = new BlockDeviceConfig(deployDefaults)

  @Shared
  Task task = Mock(Task)

  AmazonEC2 amazonEC2 = Mock(AmazonEC2)
  AmazonElasticLoadBalancing elbV2 = Mock(AmazonElasticLoadBalancing)
  AmazonELBV1 elbV1 = Mock(AmazonELBV1)
  AwsConfiguration.AmazonServerGroupProvider amazonServerGroupProvider = Mock(AwsConfiguration.AmazonServerGroupProvider)

  String instanceType
  List<AmazonBlockDevice> blockDevices

  ScalingPolicyCopier scalingPolicyCopier = Mock(ScalingPolicyCopier)

  def setup() {
    this.instanceType = 'test.large'
    this.blockDevices = [new AmazonBlockDevice(deviceName: "/dev/sdb", virtualName: "ephemeral0")]

    amazonEC2.describeImages(_) >> new DescribeImagesResult()
      .withImages(new Image().withImageId("ami-12345").withVirtualizationType('hvm').withArchitecture('x86_64'))
    amazonEC2.describeInstanceTypes(_) >> new DescribeInstanceTypesResult(instanceTypes: [
      new InstanceTypeInfo(
        instanceType: this.instanceType,
        supportedVirtualizationTypes: ['hvm'],
        processorInfo: new ProcessorInfo(supportedArchitectures: ['x86_64'], sustainedClockSpeedInGhz: 2.8),
      )])

    def rspf = Stub(RegionScopedProviderFactory) {
      forRegion(_, _) >> Stub(RegionScopedProviderFactory.RegionScopedProvider) {
        getAutoScaling() >> Stub(AmazonAutoScaling)
        getAmazonEC2() >> amazonEC2
        getAmazonElasticLoadBalancingV2(_) >> elbV2
        getAmazonElasticLoadBalancing() >> elbV1
      }
    }
    def defaults = new AwsConfiguration.DeployDefaults(iamRole: 'IamRole')
    def credsRepo = Stub(CredentialsRepository) {
      getOne("baz") >> {TestCredential.named("baz")}
    }
    this.handler = new BasicAmazonDeployHandler(
      rspf, credsRepo, amazonServerGroupProvider, defaults, scalingPolicyCopier, blockDeviceConfig, Mock(LaunchTemplateRollOutConfig)
    ) {
      @Override
      LoadBalancerLookupHelper loadBalancerLookupHelper() {
        return new LoadBalancerLookupHelper()
      }
    }

    Task task = Stub(Task) {
      getResultObjects() >> []
    }
    TaskRepository.threadLocalTask.set(task)
  }

  def cleanupSpec() {
    AutoScalingWorker.metaClass = null
    InstanceTypeUtils.metaClass = null
  }

  void "handler supports basic deploy description type"() {
    given:
    def description = new BasicAmazonDeployDescription()

    expect:
    handler.handles description
  }

  void "handler invokes a deploy feature for each specified region"() {
    setup:
    def deployCallCounts = 0
    AutoScalingWorker.metaClass.deploy = { AsgConfiguration asgConfig ->
      deployCallCounts++
      "foo"
    }
    def description = new BasicAmazonDeployDescription(amiName: "ami-12345", instanceType: this.instanceType)
    description.availabilityZones = ["us-west-1": [], "us-east-1": []]
    description.credentials = TestCredential.named('baz')

    when:
    def results = handler.handle(description, [])

    then:
    2 == deployCallCounts
    results.serverGroupNames == ['us-west-1:foo', 'us-east-1:foo']
    2 * amazonEC2.describeVpcClassicLink() >> new DescribeVpcClassicLinkResult()
  }

  void "classic load balancer names are derived from prior execution results"() {
    setup:
    def classicLbs = []
    def setlbCalls = 0
    AutoScalingWorker.metaClass.deploy = { AsgConfiguration asgCfg ->
      setlbCalls++
      classicLbs.addAll(asgCfg.classicLoadBalancers as Collection<String>)
      "foo"
    }
    def description = new BasicAmazonDeployDescription(amiName: "ami-12345", instanceType: this.instanceType)
    description.availabilityZones = ["us-east-1": []]
    description.credentials = TestCredential.named('baz')

    when:
    handler.handle(description, [new UpsertAmazonLoadBalancerResult(loadBalancers: ["us-east-1": new UpsertAmazonLoadBalancerResult.LoadBalancer("lb", "lb1.nflx")])])

    then:
    setlbCalls
    classicLbs == ['lb']
    1 * elbV1.describeLoadBalancers(_) >> new DescribeLoadBalancersResult().withLoadBalancerDescriptions(new LoadBalancerDescription().withLoadBalancerName("lb"))
    1 * amazonEC2.describeVpcClassicLink() >> new DescribeVpcClassicLinkResult()
  }

  void "handles classic load balancers"() {
    def classicLbs = []
    AutoScalingWorker.metaClass.deploy = { AsgConfiguration asgCfg ->
      classicLbs.addAll(asgCfg.classicLoadBalancers as Collection<String>)
      "foo"
    }
    def description = new BasicAmazonDeployDescription(amiName: "ami-12345", instanceType: this.instanceType, loadBalancers: ["lb"])
    description.availabilityZones = ["us-east-1": []]
    description.credentials = TestCredential.named('baz')

    when:
    handler.handle(description, [])

    then:
    1 * elbV1.describeLoadBalancers(_) >> new DescribeLoadBalancersResult().withLoadBalancerDescriptions(new LoadBalancerDescription().withLoadBalancerName("lb"))
    1 * amazonEC2.describeVpcClassicLink() >> new DescribeVpcClassicLinkResult()

    classicLbs == ['lb']
  }

  void "should store capacity on DeploymentResult"() {
    given:
    def description = new BasicAmazonDeployDescription(
        amiName: "ami-12345",
        instanceType: this.instanceType,
        capacity: new BasicAmazonDeployDescription.Capacity(min: 1, max: 10, desired: 5),
        availabilityZones: ["us-east-1": []],
        credentials: TestCredential.named('baz')
    )

    when:
    def deploymentResult = handler.handle(description, [])

    then:
    1 * amazonEC2.describeVpcClassicLink() >> new DescribeVpcClassicLinkResult()

    deploymentResult.deployments.size() == 1
    deploymentResult.deployments[0].capacity == new DeploymentResult.Deployment.Capacity(min: 1, max: 10, desired: 5)
  }

  void "handles application load balancers"() {

    def targetGroupARNs = []
    AutoScalingWorker.metaClass.deploy = { AsgConfiguration asgCfg ->
      targetGroupARNs.addAll(asgCfg.targetGroupArns as Collection<String>)
      "foo"
    }
    def description = new BasicAmazonDeployDescription(amiName: "ami-12345", instanceType: this.instanceType, targetGroups: ["tg"])
    description.availabilityZones = ["us-east-1": []]
    description.credentials = TestCredential.named('baz')

    when:
    handler.handle(description, [])

    then:
    1 * elbV2.describeTargetGroups(new DescribeTargetGroupsRequest().withNames("tg")) >> new DescribeTargetGroupsResult().withTargetGroups(new TargetGroup().withTargetGroupArn("arn:lb:targetGroup1"))
    1 * amazonEC2.describeVpcClassicLink() >> new DescribeVpcClassicLinkResult()

    targetGroupARNs == ['arn:lb:targetGroup1']
  }

  void "fails if load balancer name is not in classic load balancer"() {
    def description = new BasicAmazonDeployDescription(amiName: "ami-12345", instanceType: this.instanceType, loadBalancers: ["lb"])
    description.availabilityZones = ["us-east-1": []]
    description.credentials = TestCredential.named('baz')

    when:
    handler.handle(description, [])

    then:
    1 * elbV1.describeLoadBalancers(_) >> { throw new LBNFEV1("not found") }

    thrown(IllegalStateException)

  }

  void "should populate classic link VPC Id when classic link is enabled"() {
    def actualClassicLinkVpcId
    AutoScalingWorker.metaClass.deploy = { AsgConfiguration asgCfg ->
      actualClassicLinkVpcId = asgCfg.classicLinkVpcId
      "foo"
    }
    def description = new BasicAmazonDeployDescription(
      amiName: "ami-12345",
      instanceType: this.instanceType,
      availabilityZones: ["us-west-1": []],
      credentials: TestCredential.named('baz')
    )

    when:
    handler.handle(description, [])

    then:
    actualClassicLinkVpcId == "vpc-456"
    1 * amazonEC2.describeVpcClassicLink() >> new DescribeVpcClassicLinkResult(vpcs: [
      new VpcClassicLink(vpcId: "vpc-123", classicLinkEnabled: false),
      new VpcClassicLink(vpcId: "vpc-456", classicLinkEnabled: true),
      new VpcClassicLink(vpcId: "vpc-789", classicLinkEnabled: false)
    ])
  }

  void "should not populate classic link VPC Id when there is a subnetType"() {
    def actualClassicLinkVpcId
    AutoScalingWorker.metaClass.deploy = { AsgConfiguration asgCfg ->
      actualClassicLinkVpcId = asgCfg.classicLinkVpcId
      "foo"
    }
    def description = new BasicAmazonDeployDescription(
      amiName: "ami-12345",
      instanceType: this.instanceType,
      availabilityZones: ["us-west-1": []],
      credentials: TestCredential.named('baz'),
      subnetType: "internal"
    )

    when:
    handler.handle(description, [])

    then:
    actualClassicLinkVpcId == null
  }

  void "should not modify unlimited cpu credits if applicable, and specified"() {
    setup:
    def description = new BasicAmazonDeployDescription(amiName: "ami-12345", instanceType: "t2.large", subnetType: "internal")
    description.availabilityZones = ["us-west-1": [], "us-east-1": []]
    description.credentials = TestCredential.named('baz')
    description.unlimitedCpuCredits = unlimitedCreditsInput

    and:
    def unlimitedCpuCreditsPassed = null
    AutoScalingWorker.metaClass.deploy = { AsgConfiguration asgCfg ->
      unlimitedCpuCreditsPassed = asgCfg.unlimitedCpuCredits
      "foo"
    }

    when:
    handler.handle(description, [])

    then:
    unlimitedCpuCreditsPassed == unlimitedCreditsInput

    where:
    unlimitedCreditsInput << [true, false]
  }

  void "should set unlimited cpu credits to the default false only if applicable to all instance types"() {

    expect:
    handler.getDefaultUnlimitedCpuCredits(instanceTypes as Set) == expectedDefault

    where:
    instanceTypes               || expectedDefault
    ["t2.small"]                ||  false
    ["c3.large"]                ||  null
    ["t2.large", "t3.large"]    ||  false
    ["t2.small", "c3.large"]    ||  null
    ["m4.large", "c3.large"]    ||  null
  }

  void "should send instance class block devices to AutoScalingWorker when matched and none are specified and absence of source ASG"() {
    setup:
    def deployCallCounts = 0
    def setBlockDevices = []
    AutoScalingWorker.metaClass.deploy = { AsgConfiguration asgCfg ->
      deployCallCounts++
      setBlockDevices = asgCfg.blockDevices
      "foo"
    }
    def description = new BasicAmazonDeployDescription(amiName: "ami-12345", instanceType: this.instanceType)
    description.instanceType = "m3.medium"
    description.availabilityZones = ["us-west-1": [], "us-east-1": []]
    description.credentials = TestCredential.named('baz')

    when:
    def results = handler.handle(description, [])

    then:
    2 == deployCallCounts
    results.serverGroupNames == ['us-west-1:foo', 'us-east-1:foo']
    setBlockDevices == this.blockDevices
    2 * amazonEC2.describeVpcClassicLink() >> new DescribeVpcClassicLinkResult()
    2 * amazonEC2.describeImages(_) >> new DescribeImagesResult().withImages(new Image().withImageId('ami-12345')
      .withVirtualizationType('hvm'))
  }

  void "should favour explicit description block devices over default config"() {
    setup:
    def deployCallCounts = 0
    List<AmazonBlockDevice> setBlockDevices = []
    AutoScalingWorker.metaClass.deploy = { AsgConfiguration asgCfg ->
      setBlockDevices = asgCfg.blockDevices
      deployCallCounts++
      "foo"
    }
    def description = new BasicAmazonDeployDescription(amiName: "ami-12345", instanceType: this.instanceType)
    description.blockDevices = [new AmazonBlockDevice(deviceName: "/dev/sdb", size: 125)]
    description.availabilityZones = ["us-west-1": [], "us-east-1": []]
    description.credentials = TestCredential.named('baz')

    when:
    def results = handler.handle(description, [])

    then:
    2 == deployCallCounts
    results.serverGroupNames == ['us-west-1:foo', 'us-east-1:foo']
    setBlockDevices.size()
    setBlockDevices == description.blockDevices
    2 * amazonEC2.describeVpcClassicLink() >> new DescribeVpcClassicLinkResult()
    2 * amazonEC2.describeImages(_) >> new DescribeImagesResult().withImages(new Image().withImageId('ami-12345')
      .withVirtualizationType('hvm').withArchitecture('x86_64'))
  }

  @Unroll
  void "should favour ami block device mappings over explicit description block devices and default config, if useAmiBlockDeviceMappings is set"() {
    setup:
    def deployCallCounts = 0
    List<AmazonBlockDevice> setBlockDevices = []
    AutoScalingWorker.metaClass.deploy = { AsgConfiguration asgCfg ->
      deployCallCounts++
      setBlockDevices = asgCfg.blockDevices
      "foo"
    }
    def description = new BasicAmazonDeployDescription(amiName: "ami-12345", instanceType: this.instanceType)
    description.blockDevices = [new AmazonBlockDevice(deviceName: "/dev/sdb", size: 125)]
    description.useAmiBlockDeviceMappings = useAmiBlockDeviceMappings
    description.availabilityZones = ["us-west-1": [], "us-east-1": []]
    description.credentials = TestCredential.named('baz')

    when:
    def results = handler.handle(description, [])

    then:
    2 == deployCallCounts
    results.serverGroupNames == ['us-west-1:foo', 'us-east-1:foo']
    2 * amazonEC2.describeVpcClassicLink() >> new DescribeVpcClassicLinkResult()
    2 * amazonEC2.describeImages(_) >>
      new DescribeImagesResult()
        .withImages(new Image()
        .withImageId('ami-12345')
          .withBlockDeviceMappings([new com.amazonaws.services.ec2.model.BlockDeviceMapping()
                                      .withDeviceName("/dev/sdh")
                                      .withEbs(new EbsBlockDevice().withVolumeSize(500))])
          .withVirtualizationType('hvm')
          .withArchitecture('x86_64'))
    setBlockDevices == expectedBlockDevices

    where:
    useAmiBlockDeviceMappings | expectedBlockDevices
    true                      | [new AmazonBlockDevice(deviceName: "/dev/sdh", size: 500)]
    false                     | [new AmazonBlockDevice(deviceName: "/dev/sdb", size: 125)]
    null                      | [new AmazonBlockDevice(deviceName: "/dev/sdb", size: 125)]
  }

  void "should resolve amiId from amiName"() {
    setup:
    def deployCallCounts = 0
    AutoScalingWorker.metaClass.deploy = { AsgConfiguration asgCfg ->
      deployCallCounts++
      "foo"
    }

    def description = new BasicAmazonDeployDescription(amiName: "the-greatest-ami-in-the-world", instanceType: this.instanceType, availabilityZones: ['us-west-1': []])
    description.credentials = TestCredential.named('baz')

    when:
    def results = handler.handle(description, [])

    then:
    1 * amazonEC2.describeImages(_) >> { DescribeImagesRequest req ->
      assert req.filters.size() == 1
      assert req.filters.first().name == 'name'
      assert req.filters.first().values == ['the-greatest-ami-in-the-world']

      return new DescribeImagesResult().withImages(new Image().withImageId('ami-12345').withVirtualizationType('hvm').withArchitecture('x86_64'))
    }
    1 * amazonEC2.describeVpcClassicLink() >> new DescribeVpcClassicLinkResult()
    deployCallCounts == 1
  }

  @Unroll
  void "should copy block devices from source provider using a launch configuration if not specified explicitly and instance types match"() {
    given:
    def asgService = Mock(AsgService) {
      expectedCallsToAws * getLaunchConfiguration(_) >> {
        return new LaunchConfiguration()
          .withBlockDeviceMappings(new BlockDeviceMapping().withDeviceName("OLD_DEVICE")
        )
      }
    }
    def sourceRegionScopedProvider = Mock(RegionScopedProvider) {
      expectedCallsToAws * getAsgService() >> { return asgService }
      1 * getAutoScaling() >> {
        return Mock(AmazonAutoScaling) {
          1 * describeAutoScalingGroups(_) >> {
            return new DescribeAutoScalingGroupsResult().withAutoScalingGroups(
              new AutoScalingGroup().withLaunchConfigurationName("launchConfig"))
          }
        }
      }
    }

    when:
    def targetDescription = handler.copySourceAttributes(
      sourceRegionScopedProvider, "sourceAsg", null, description
    )

    then:
    targetDescription.blockDevices*.deviceName == expectedBlockDevices

    where:
    description                                                                                        | expectedCallsToAws || expectedBlockDevices
    new BasicAmazonDeployDescription(instanceType: this.instanceType)                                  |       2            || ["OLD_DEVICE"]
    new BasicAmazonDeployDescription(blockDevices: [], instanceType: this.instanceType)                |       0            || []
    new BasicAmazonDeployDescription(blockDevices: [new AmazonBlockDevice(deviceName: "DEVICE")],
                                                                      instanceType: this.instanceType) |       0            || ["DEVICE"]
  }

  @Unroll
  void "should copy block devices from source provider using a launch template if not specified explicitly and instance types match"() {
    given:
    def launchTemplateVersion = new LaunchTemplateVersion(
      launchTemplateName: "lt",
      launchTemplateId: "id",
      versionNumber: 0,
      launchTemplateData: new ResponseLaunchTemplateData(
        blockDeviceMappings: [new LaunchTemplateBlockDeviceMapping(deviceName: "OLD_DEVICE")]
      )
    )

    def launchTemplate = new LaunchTemplateSpecification(
      launchTemplateName: launchTemplateVersion.launchTemplateName,
      launchTemplateId: launchTemplateVersion.launchTemplateId,
      version: launchTemplateVersion.versionNumber.toString(),
    )

    and:
    def launchTemplateService = Mock(LaunchTemplateService) {
      getLaunchTemplateVersion({it.launchTemplateId == launchTemplate.launchTemplateId} as LaunchTemplateSpecification) >> Optional.of(launchTemplateVersion)
    }

    def autoScaling = Mock(AmazonAutoScaling) {
      describeAutoScalingGroups(_) >> {
        return new DescribeAutoScalingGroupsResult().withAutoScalingGroups(
          new AutoScalingGroup().withLaunchTemplate(
            launchTemplate
          ))
      }
    }

    def sourceRegionScopedProvider = Mock(RegionScopedProvider) {
      getLaunchTemplateService() >> launchTemplateService
      getAutoScaling() >> autoScaling
    }

    when:
    def targetDescription = handler.copySourceAttributes(
      sourceRegionScopedProvider, "sourceAsg", null, description
    )

    then:
    targetDescription.blockDevices*.deviceName == expectedBlockDevices

    where:
    description                                                                                        || expectedBlockDevices
    new BasicAmazonDeployDescription(instanceType: this.instanceType)                                  || ["OLD_DEVICE"]
    new BasicAmazonDeployDescription(blockDevices: [], instanceType: this.instanceType)                || []
    new BasicAmazonDeployDescription(blockDevices: [new AmazonBlockDevice(deviceName: "DEVICE")],
                                                                      instanceType: this.instanceType) || ["DEVICE"]
  }

  @Unroll
  void "should copy subnet ids from source when available and not explicitly specified"() {
    given:
    def regionScopedProvider = new RegionScopedProviderFactory().forRegion(testCredentials, "us-west-2")
    def description = new BasicAmazonDeployDescription(
      instanceType: this.instanceType,
      subnetIds: subnetIds,
      copySourceCustomBlockDeviceMappings: false,
      tags: [:]
    )

    when:
    handler.copySourceAttributes(regionScopedProvider, "application-v002", false, description)

    then:
    (subnetIds ? 0 : 1) * amazonServerGroupProvider.getServerGroup("test", "us-west-2", "application-v002") >> {
      def sourceServerGroup = new AmazonServerGroup(asg: [:])
      if (tagValue) {
        sourceServerGroup.asg.tags = [[key: SUBNET_ID_OVERRIDE_TAG, value: tagValue]]
      }
      return sourceServerGroup
    }
    0 * _

    description.subnetIds == expectedSubnetIds
    description.tags == (expectedSubnetIds ? [(SUBNET_ID_OVERRIDE_TAG): expectedSubnetIds.join(",")] : [:])

    where:
    subnetIds    | tagValue            || expectedSubnetIds
    null         | null                || null
    null         | "subnet-1,subnet-2" || ["subnet-1", "subnet-2"]
    ["subnet-1"] | "subnet-1,subnet-2" || ["subnet-1"]               // description takes precedence over source asg tag
  }

  @Unroll
  void "copy source block devices #copySourceBlockDevices feature flags"() {
    given:
    if (copySourceBlockDevices != null) {
      description.copySourceCustomBlockDeviceMappings = copySourceBlockDevices // default copySourceCustomBlockDeviceMappings is true
    }
    int expectedCallsToAws = description.copySourceCustomBlockDeviceMappings ? 2 : 0
    def asgService = Mock(AsgService) {
      (expectedCallsToAws) * getLaunchConfiguration(_) >> {
        return new LaunchConfiguration()
          .withBlockDeviceMappings(new BlockDeviceMapping().withDeviceName("OLD_DEVICE")
        )
      }
    }
    def sourceRegionScopedProvider = Mock(RegionScopedProvider) {
      (expectedCallsToAws) * getAsgService() >> { return asgService }
      1 * getAutoScaling() >> {
        return Mock(AmazonAutoScaling) {
          1 * describeAutoScalingGroups(_) >> {
            return new DescribeAutoScalingGroupsResult().withAutoScalingGroups(
              new AutoScalingGroup().withLaunchConfigurationName('foo'))
          }
        }
      }
    }

    when:
    def targetDescription = handler.copySourceAttributes(
      sourceRegionScopedProvider, "sourceAsg", true, description
    )

    then:
    targetDescription.blockDevices?.deviceName == expectedBlockDevices

    where:
    description                                                  | copySourceBlockDevices || expectedBlockDevices
    new BasicAmazonDeployDescription(instanceType: this.instanceType) | null                   || ["OLD_DEVICE"]
    new BasicAmazonDeployDescription(instanceType: this.instanceType) | true                   || ["OLD_DEVICE"]
    new BasicAmazonDeployDescription(instanceType: this.instanceType) | false                  || null
  }

  void 'should fail if useSourceCapacity requested, and source not available'() {
    given:
    def description = new BasicAmazonDeployDescription(capacity: descriptionCapacity, instanceType: this.instanceType)
    def sourceRegionScopedProvider = null

    when:
    handler.copySourceAttributes(
      sourceRegionScopedProvider, "sourceAsg", useSource, description
    )

    then:
    thrown(IllegalStateException)

    where:
    useSource = true
    descriptionCapacity = new BasicAmazonDeployDescription.Capacity(5, 5, 5)
  }

  void 'should fail if ASG not found and useSourceCapacity requested'() {
    given:
    def description = new BasicAmazonDeployDescription(capacity: descriptionCapacity, instanceType: this.instanceType)
    def sourceRegionScopedProvider = Stub(RegionScopedProvider) {
      getAutoScaling() >> Stub(AmazonAutoScaling) {
        describeAutoScalingGroups(_) >> new DescribeAutoScalingGroupsResult()
      }
    }

    when:
    handler.copySourceAttributes(
      sourceRegionScopedProvider, "sourceAsg", useSource, description
    )
    then:
    thrown(IllegalStateException)

    where:
    useSource = true
    descriptionCapacity = new BasicAmazonDeployDescription.Capacity(5, 5, 5)
  }

  void 'should copy capacity from source if specified'() {
    given:
    def description = new BasicAmazonDeployDescription(capacity: descriptionCapacity, instanceType: this.instanceType)
    def asgService = Stub(AsgService) {
      getLaunchConfiguration(_) >> new LaunchConfiguration()
    }
    def sourceRegionScopedProvider = Stub(RegionScopedProvider) {
      getAsgService() >> asgService
      getAutoScaling() >> Stub(AmazonAutoScaling) {
        describeAutoScalingGroups(_) >> {
          new DescribeAutoScalingGroupsResult().withAutoScalingGroups(
            new AutoScalingGroup()
              .withLaunchConfigurationName('lc')
              .withMinSize(sourceCapacity.min)
              .withMaxSize(sourceCapacity.max)
              .withDesiredCapacity(sourceCapacity.desired)

          )
        }
      }
    }

    when:
    def targetDescription = handler.copySourceAttributes(
      sourceRegionScopedProvider, "sourceAsg", useSource, description
    )

    then:
    targetDescription.capacity == expectedCapacity

    where:
    useSource << [null, false, true]
    descriptionCapacity = new BasicAmazonDeployDescription.Capacity(5, 5, 5)
    sourceCapacity = new BasicAmazonDeployDescription.Capacity(7, 7, 7)
    expectedCapacity = useSource ? sourceCapacity : descriptionCapacity
  }

  @Unroll
  void "should copy scaling policies and scheduled actions"() {
    given:
    String sourceRegion = "us-east-1"
    def sourceRegionScopedProvider = Mock(RegionScopedProvider) {
      1 * getAsgReferenceCopier(testCredentials, targetRegion) >> {
        return Mock(AsgReferenceCopier) {
          1 * copyScheduledActionsForAsg(task, sourceAsgName, targetAsgName)
        }
      }
    }

    when:
    handler.copyScalingPoliciesAndScheduledActions(
      task, sourceRegionScopedProvider, testCredentials, testCredentials, sourceAsgName, targetAsgName, sourceRegion, targetRegion
    )

    then:
    1 * scalingPolicyCopier.copyScalingPolicies(task, sourceAsgName, targetAsgName, testCredentials, testCredentials, sourceRegion, targetRegion)

    where:
    sourceAsgName | targetRegion | targetAsgName
    "sourceAsg"   | "us-west-1"  | "targetAsg"

  }

  @Unroll
  void 'should create #numHooksExpected lifecycle hooks'() {
    given:
    def credentials = TestCredential.named('test', [lifecycleHooks: accountLifecycleHooks])

    def description = new BasicAmazonDeployDescription(instanceType: this.instanceType, lifecycleHooks: lifecycleHooks, includeAccountLifecycleHooks: includeAccount)

    when:
    def result = BasicAmazonDeployHandler.getLifecycleHooks(credentials, description)

    then:
    result.size() == numHooksExpected

    where:
    accountLifecycleHooks                                                                                                  | lifecycleHooks                                                                          | includeAccount || numHooksExpected
    []                                                                                                                     | []                                                                                      | true           || 0
    [new AmazonCredentials.LifecycleHook('role-arn', 'target-arn', 'autoscaling:EC2_INSTANCE_LAUNCHING', 3600, 'ABANDON')] | []                                                                                      | true           || 1
    []                                                                                                                     | [new AmazonAsgLifecycleHook(roleARN: 'role-arn', notificationTargetARN: 'target-arn')]  | true           || 1
    [new AmazonCredentials.LifecycleHook('role-arn', 'target-arn', 'autoscaling:EC2_INSTANCE_LAUNCHING', 3600, 'ABANDON')] | [new AmazonAsgLifecycleHook(roleARN: 'role-arn2', notificationTargetARN: 'target-arn')] | true           || 2
    [new AmazonCredentials.LifecycleHook('role-arn', 'target-arn', 'autoscaling:EC2_INSTANCE_LAUNCHING', 3600, 'ABANDON')] | [new AmazonAsgLifecycleHook(roleARN: 'role-arn2', notificationTargetARN: 'target-arn')] | false          || 1
    [new AmazonCredentials.LifecycleHook('role-arn', 'target-arn', 'autoscaling:EC2_INSTANCE_LAUNCHING', 3600, 'ABANDON')] | []                                                                                      | false          || 0
  }

  void 'should raise exception for unsupported Transition'() {
    def credentials = TestCredential.named('test', [
      lifecycleHooks: [new AmazonCredentials.LifecycleHook('arn', 'arn', 'UNSUPPORTED_TRANSITION', 3600, 'ABANDON')]
    ])

    def description = new BasicAmazonDeployDescription(
      includeAccountLifecycleHooks: true, instanceType: this.instanceType
    )

    when:
    BasicAmazonDeployHandler.getLifecycleHooks(credentials, description)

    then:
    thrown(IllegalArgumentException)
  }

  @Unroll
  void "should throw exception when instance type does not match image virtualization type"() {
    setup:
    def description = new BasicAmazonDeployDescription(amiName: "a-terrible-ami", availabilityZones: ['us-west-1': []])
    description.credentials = TestCredential.named('baz')
    description.instanceType = instanceType

    when:
    handler.handle(description, [])

    then:
    1 * amazonEC2.describeImages(_) >> new DescribeImagesResult().withImages(new Image().withImageId('ami-12345')
      .withVirtualizationType(virtualizationType))
    1 * amazonEC2.describeVpcClassicLink() >> new DescribeVpcClassicLinkResult()
    1 * amazonEC2.describeInstanceTypes(_) >> new DescribeInstanceTypesResult(instanceTypes: [
      new InstanceTypeInfo(instanceType: "r3.xlarge", supportedVirtualizationTypes: ["hvm"]),
      new InstanceTypeInfo(instanceType: "t3.micro", supportedVirtualizationTypes: ["hvm"])
    ])

    and:
    thrown IllegalArgumentException

    where:
    instanceType | virtualizationType
    'r3.xlarge'  | 'paravirtual'
    't3.micro'   | 'paravirtual'
  }

  @Unroll
  void "should not throw exception when instance type matches image virtualization type"() {
    setup:
    def description = new BasicAmazonDeployDescription(amiName: "a-cool-ami", availabilityZones: ['us-west-1': []])
    description.credentials = TestCredential.named('baz')
    description.instanceType = instanceType

    when:
    handler.handle(description, [])

    then:
    1 * amazonEC2.describeImages(_) >> new DescribeImagesResult().withImages(new Image().withImageId('ami-12345')
      .withVirtualizationType(virtualizationType)
      .withArchitecture("x86_64"))
    1 * amazonEC2.describeVpcClassicLink() >> new DescribeVpcClassicLinkResult()
    1 * amazonEC2.describeInstanceTypes(_) >> new DescribeInstanceTypesResult(instanceTypes: [
      new InstanceTypeInfo(
        instanceType: this.instanceType,
        supportedVirtualizationTypes: [virtualizationType],
        processorInfo: new ProcessorInfo(supportedArchitectures: ["x86_64"], sustainedClockSpeedInGhz: 2.8),
      )])

    and:
    noExceptionThrown()

    where:
    instanceType  | virtualizationType
    'm1.large'    | 'pv'
    'm4.medium'   | 'hvm'
    'c3.large'    | 'hvm'
    'c3.xlarge'   | 'paravirtual'
    'mystery.big' | 'hvm'
    'what.the'    | 'heck'
  }

  @Unroll
  void "should regenerate block device mappings if instance type changes"() {
    setup:
    def description = new BasicAmazonDeployDescription(
      instanceType: targetInstanceType,
      blockDevices: descriptionBlockDevices
    )
    def launchConfiguration = new LaunchConfiguration()
      .withLaunchConfigurationName('lc')
      .withInstanceType(sourceInstanceType)
      .withBlockDeviceMappings(sourceBlockDevices?.collect {
      new BlockDeviceMapping().withVirtualName(it.virtualName).withDeviceName(it.deviceName)
    })
    def sourceAsg = new AutoScalingGroup()
      .withLaunchConfigurationName(launchConfiguration.getLaunchConfigurationName())

    def asgService = Mock(AsgService) {
      getLaunchConfiguration(_) >> launchConfiguration
    }
    def sourceRegionScopedProvider = Stub(RegionScopedProvider) {
      getAsgService() >> asgService
      getAutoScaling() >> Stub(AmazonAutoScaling) {
        describeAutoScalingGroups(_) >> {
          new DescribeAutoScalingGroupsResult().withAutoScalingGroups(
            sourceAsg)
        }
      }
    }

    when:
    def blockDeviceMappings = handler.buildBlockDeviceMappingsFromSourceAsg(sourceRegionScopedProvider, sourceAsg, description)

    then:
    blockDeviceMappings == expectedTargetBlockDevices

    where:
    sourceInstanceType | targetInstanceType | sourceBlockDevices                              | descriptionBlockDevices || expectedTargetBlockDevices
    "c3.xlarge"        | "c4.xlarge"        | bD("c3.xlarge")                                 | bD("c3.xlarge")         || bD("c3.xlarge")                                 // use the explicitly provided block devices even if instance type has changed
    "c3.xlarge"        | "c4.xlarge"        | bD("c3.xlarge")                                 | []                      || []                                              // use the explicitly provided block devices even if an empty list
    "c3.xlarge"        | "c4.xlarge"        | bD("c3.xlarge")                                 | null                    || bD("c4.xlarge")                                 // was using default block devices, continue to use default block devices for targetInstanceType
    "c3.xlarge"        | "c4.xlarge"        | [new AmazonBlockDevice(deviceName: "/dev/xxx")] | null                    || [new AmazonBlockDevice(deviceName: "/dev/xxx")] // custom block devices should be preserved
    "c3.xlarge"        | "r4.100xlarge"     | bD("c3.xlarge")                                 | null                    || [deployDefaults.unknownInstanceTypeBlockDevice] // no mapping for r4.100xlarge, use the default for unknown instance types
  }

  @Unroll
  void "should regenerate block device mappings conditionally, for source ASG with mixed instances policy"() {
    setup:
    def launchTemplateVersion = new LaunchTemplateVersion(
      launchTemplateName: "lt",
      launchTemplateId: "id",
      versionNumber: 0,
      launchTemplateData: new ResponseLaunchTemplateData(
        instanceType: sourceInstanceType,
        blockDeviceMappings: sourceBlockDevices?.collect {
          new LaunchTemplateBlockDeviceMapping().withVirtualName(it.virtualName).withDeviceName(it.deviceName)
        }))
    def mixedInstancesPolicy = new MixedInstancesPolicy()
      .withLaunchTemplate(new LaunchTemplate()
        .withLaunchTemplateSpecification(new LaunchTemplateSpecification()
          .withLaunchTemplateId(launchTemplateVersion.launchTemplateId)
          .withLaunchTemplateName(launchTemplateVersion.launchTemplateName)
          .withVersion(launchTemplateVersion.versionNumber.toString()))
        .withOverrides(
          new LaunchTemplateOverrides().withInstanceType("c3.large").withWeightedCapacity("2"),
          new LaunchTemplateOverrides().withInstanceType("c3.xlarge").withWeightedCapacity("4")))

    def sourceAsg = new AutoScalingGroup().withMixedInstancesPolicy(mixedInstancesPolicy)

    and:
    def launchTemplateService = Mock(LaunchTemplateService) {
      getLaunchTemplateVersion({it.launchTemplateId == launchTemplateVersion.launchTemplateId} as LaunchTemplateSpecification) >> Optional.of(launchTemplateVersion)
    }

    def sourceRegionScopedProvider = Mock(RegionScopedProvider) {
      getLaunchTemplateService() >> launchTemplateService
      getAutoScaling() >> Stub(AmazonAutoScaling) {
        describeAutoScalingGroups(_) >> {
          new DescribeAutoScalingGroupsResult().withAutoScalingGroups(sourceAsg)
        }
      }
    }

    and:
    def description = new BasicAmazonDeployDescription(
      instanceType: descInstanceType,
      launchTemplateOverridesForInstanceType: [
        new BasicAmazonDeployDescription.LaunchTemplateOverridesForInstanceType(instanceType: "c4.large", weightedCapacity: "2"),
        new BasicAmazonDeployDescription.LaunchTemplateOverridesForInstanceType(instanceType: "c4.xlarge", weightedCapacity: "4")
      ],
      blockDevices: descBlockDevices
    )

    when:
    def blockDeviceMappings = handler.buildBlockDeviceMappingsFromSourceAsg(sourceRegionScopedProvider, sourceAsg, description)

    then:
    blockDeviceMappings == expectedTargetBlockDevices

    where:
    sourceInstanceType| descInstanceType|    sourceBlockDevices    | descBlockDevices   || expectedTargetBlockDevices
    "c3.xlarge"       | "c4.xlarge"     |      bD("c3.xlarge")     | bD("c3.xlarge")    || bD("c3.xlarge")                                 // use the explicitly provided block devices even if instance type has changed
    "c3.xlarge"       | "c4.xlarge"     |      bD("c3.xlarge")     | []                 || []                                              // use the explicitly provided block devices even if an empty list
    "c3.xlarge"       | "c4.xlarge"     |      bD("c3.xlarge")     | null               || bD("c4.xlarge")                                 // source ASG used default block devices, so use default block devices for top-level instance type in description i.e. descInstanceType
    "c3.xlarge"       | "c4.xlarge"     |[new AmazonBlockDevice(
                                          deviceName: "/dev/xxx")] | null               || [new AmazonBlockDevice(deviceName: "/dev/xxx")] // custom block devices should be preserved
    "c3.xlarge"       | "c4.100xlarge"  |      bD("c3.xlarge")     | null               || [deployDefaults.unknownInstanceTypeBlockDevice] // source ASG used default bD, so use default bD but no mapping for c4.200xlarge, use the default for unknown instance types
    "c3.xlarge"       | "c3.xlarge"     |      bD("c3.xlarge")     | null               || bD("c3.xlarge")                                 // top-level instance types match, use source ASG's block devices
  }

  @Unroll
  void "should substitute {{application}} in iamRole"() {
    given:
    def description = new BasicAmazonDeployDescription(application: application, iamRole: iamRole, instanceType: this.instanceType)
    def deployDefaults = new AwsConfiguration.DeployDefaults(iamRole: defaultIamRole)

    expect:
    BasicAmazonDeployHandler.iamRole(description, deployDefaults) == expectedIamRole

    where:
    application | iamRole                  | defaultIamRole           || expectedIamRole
    "app"       | "iamRole"                | "defaultIamRole"         || "iamRole"
    "app"       | null                     | "defaultIamRole"         || "defaultIamRole"
    "app"       | "{{application}}IamRole" | null                     || "appIamRole"
    "app"       | null                     | "{{application}}IamRole" || "appIamRole"
    null        | null                     | "{{application}}IamRole" || "{{application}}IamRole"
  }

  @Unroll
  void "should apply app/stack/detail tags when `addAppStackDetailTags` is enabled"() {
    given:
    def deployDefaults = new DeployDefaults(addAppStackDetailTags: addAppStackDetailTags)
    def description = new BasicAmazonDeployDescription(
      application: application,
      instanceType: this.instanceType,
      stack: stack,
      freeFormDetails: details,
      tags: initialTags
    )

    expect:
    buildTags("1", "2", "3") == ["spinnaker:application": "1", "spinnaker:stack": "2", "spinnaker:details": "3"]
    buildTags("1", null, "3") == ["spinnaker:application": "1", "spinnaker:details": "3"]
    buildTags("1", null, null) == ["spinnaker:application": "1"]
    buildTags(null, null, null) == [:]

    when:
    def updatedDescription = BasicAmazonDeployHandler.applyAppStackDetailTags(deployDefaults, description)

    then:
    updatedDescription.tags == expectedTags

    where:
    addAppStackDetailTags | application | stack   | details   | initialTags                          || expectedTags
    false                 | "app"       | "stack" | "details" | [foo: "bar"]                         || ["foo": "bar"]
    true                  | "app"       | "stack" | "details" | [foo: "bar"]                         || [foo: "bar"] + buildTags("app", "stack", "details")
    true                  | "app"       | "stack" | "details" | buildTags("1", "2", "3")             || buildTags("app", "stack", "details")    // override any previous app/stack/details tags
    true                  | "app"       | null    | "details" | [:]                                  || buildTags("app", null, "details")       // avoid creating tags with null values
    true                  | "app"       | null    | "details" | buildTags("app", "stack", "details") || buildTags("app", null, "details")       // should remove pre-existing tags if invalid
    true                  | null        | null    | null      | buildTags("app", "stack", "details") || [:]                                     // should remove pre-existing tags if invalid
    true                  | "app"       | null    | null      | [:]                                  || buildTags("app", null, null)
    true                  | null        | null    | null      | [:]                                  || buildTags(null, null, null)
  }

  void "should not copy reserved aws tags"() {
    expect:
    BasicAmazonDeployHandler.cleanTags(tags) == expected

    where:
    tags                       || expected
    null                       || [:]
    [:]                        || [:]
    ["a": "a"]                 || ["a": "a"]
    ["a": "a", "aws:foo": "3"] || ["a": "a"]
  }

  private static Map buildTags(String application, String stack, String details) {
    def tags = [:]
    if (application) {
      tags["spinnaker:application"] = application
    }
    if (stack) {
      tags["spinnaker:stack"] = stack
    }
    if (details) {
      tags["spinnaker:details"] = details
    }
    return tags
  }

  private Collection<AmazonBlockDevice> bD(String instanceType) {
    return blockDeviceConfig.getBlockDevicesForInstanceType(instanceType)
  }
}
