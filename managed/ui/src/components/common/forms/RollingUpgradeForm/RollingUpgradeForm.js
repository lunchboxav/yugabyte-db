// Copyright (c) YugaByte, Inc.

import _ from 'lodash';
import React, { Component } from 'react';
import { Field, FieldArray } from 'redux-form';
import { Col, Alert } from 'react-bootstrap';
import { YBModal, YBInputField, YBSelectWithLabel, YBToggle, YBCheckBox } from '../fields';
import { isNonEmptyArray } from '../../../../utils/ObjectUtils';
import { getPromiseState } from '../../../../utils/PromiseUtils';
import { getPrimaryCluster } from '../../../../utils/UniverseUtils';
import { isDefinedNotNull, isNonEmptyObject } from '../../../../utils/ObjectUtils';
import './RollingUpgradeForm.scss';
import { EncryptionInTransit } from './EncryptionInTransit';
import GFlagComponent from '../../../universes/UniverseForm/GFlagComponent';
import { FlexShrink, FlexContainer } from '../../flexbox/YBFlexBox';
import clsx from 'clsx';
import WarningIcon from './images/warning.svg';

export default class RollingUpgradeForm extends Component {
  constructor(props) {
    super(props);
    this.state = { formConfirmed: false };
  }

  toggleConfirmValidation = () => {
    this.setState({ formConfirmed: !this.state.formConfirmed });
  };

  componentWillUnmount() {
    this.resetAndClose();
  }

  getCurrentVersion = () => {
    const { universe } = this.props;
    let currentVersion = null;
    if (
      isDefinedNotNull(universe.currentUniverse.data) &&
      isNonEmptyObject(universe.currentUniverse.data)
    ) {
      const primaryCluster = getPrimaryCluster(
        universe.currentUniverse.data.universeDetails.clusters
      );
      currentVersion = primaryCluster?.userIntent?.ybSoftwareVersion;
    }
    return currentVersion;
  };

  setRollingUpgradeProperties = (values) => {
    const {
      modal: { visibleModal },
      universe: {
        currentUniverse: {
          data: {
            universeDetails: { clusters, nodePrefix },
            universeUUID
          }
        }
      },
      overrideIntentParams,
      resetLocation
    } = this.props;

    const payload = {};
    switch (visibleModal) {
      case 'softwareUpgradesModal': {
        payload.taskType = 'Software';
        payload.upgradeOption = values.rollingUpgrade ? 'Rolling' : 'Non-Rolling';
        break;
      }
      case 'systemdUpgrade': {
        payload.taskType = 'Systemd';
        payload.upgradeOption = 'Rolling';
        var systemdBoolean = true;
        break;
      }
      case 'gFlagsModal': {
        payload.taskType = 'GFlags';
        payload.upgradeOption = values.upgradeOption;
        break;
      }
      case 'tlsConfigurationModal': {
        payload.taskType = 'Certs';
        payload.upgradeOption = values.rollingUpgrade ? 'Rolling' : 'Non-Rolling';
        payload.certUUID = values.tlsCertificate;
        break;
      }
      case 'rollingRestart': {
        payload.taskType = 'Restart';
        payload.upgradeOption = 'Rolling';
        break;
      }
      case 'resizeNodesModal': {
        payload.taskType = 'Resize_Node';
        payload.upgradeOption = 'Rolling';
        break;
      }
      default:
        return;
    }

    const primaryCluster = _.cloneDeep(getPrimaryCluster(clusters));
    if (!isDefinedNotNull(primaryCluster)) {
      return;
    }
    payload.ybSoftwareVersion = values.ybSoftwareVersion;
    payload.universeUUID = universeUUID;
    payload.nodePrefix = nodePrefix;
    const masterGFlagList = [];
    const tserverGFlagList = [];
    if (isNonEmptyArray(values?.gFlags)) {
      values.gFlags.forEach((flag) => {
        if (flag?.hasOwnProperty('MASTER'))
          masterGFlagList.push({ name: flag?.Name, value: flag['MASTER'] });
        if (flag?.hasOwnProperty('TSERVER'))
          tserverGFlagList.push({ name: flag?.Name, value: flag['TSERVER'] });
      });
    }
    primaryCluster.userIntent.ybSoftwareVersion = values.ybSoftwareVersion;
    primaryCluster.userIntent.masterGFlags = masterGFlagList;
    primaryCluster.userIntent.tserverGFlags = tserverGFlagList;
    primaryCluster.userIntent.useSystemd = systemdBoolean;
    payload.clusters = [primaryCluster];
    payload.sleepAfterMasterRestartMillis = values.timeDelay * 1000;
    payload.sleepAfterTServerRestartMillis = values.timeDelay * 1000;
    if (overrideIntentParams) {
      if (overrideIntentParams.instanceType) {
        primaryCluster.userIntent.instanceType = overrideIntentParams.instanceType;
      }
      if (overrideIntentParams.volumeSize) {
        primaryCluster.userIntent.deviceInfo.volumeSize = overrideIntentParams.volumeSize;
      }
    }

    this.props.submitRollingUpgradeForm(payload, universeUUID).then((response) => {
      if (response.payload.status === 200) {
        this.props.fetchCurrentUniverse(universeUUID);
        this.props.fetchUniverseMetadata();
        this.props.fetchCustomerTasks();
        this.props.fetchUniverseTasks(universeUUID);
        if (resetLocation) {
          window.location.href = `/universes/${universeUUID}`;
        } else {
          this.resetAndClose();
        }
      }
    });
  };

  resetAndClose = () => {
    this.props.onHide();
    this.props.reset();
    this.props.resetRollingUpgrade();
    this.setState({ formConfirmed: false });
  };

  render() {
    const {
      modalVisible,
      handleSubmit,
      universe,
      modal: { visibleModal },
      universe: { error },
      softwareVersions,
      formValues,
      certificates
    } = this.props;

    const currentVersion = this.getCurrentVersion();
    const submitAction = handleSubmit(this.setRollingUpgradeProperties);

    const softwareVersionOptions = softwareVersions.map((item, idx) => (
      <option key={idx} disabled={item === currentVersion} value={item}>
        {item}
      </option>
    ));

    const tlsCertificateOptions = certificates.map((item) => (
      <option
        key={item.uuid}
        disabled={item.uuid === universe.currentUniverse?.data?.universeDetails?.rootCA}
        value={item.uuid}
      >
        {item.label}
      </option>
    ));

    const errorAlert = getPromiseState(universe.rollingUpgrade).isError() && (
      <Alert bsStyle="danger" variant="danger">
        {universe.rollingUpgrade.error
          ? JSON.stringify(universe.rollingUpgrade.error)
          : 'Something went wrong'}
      </Alert>
    );

    switch (visibleModal) {
      case 'softwareUpgradesModal': {
        return (
          <YBModal
            className={getPromiseState(universe.rollingUpgrade).isError() ? 'modal-shake' : ''}
            visible={modalVisible}
            formName="RollingUpgradeForm"
            onHide={this.resetAndClose}
            submitLabel="Upgrade"
            showCancelButton
            title="Upgrade Software"
            onFormSubmit={submitAction}
            error={error}
            footerAccessory={
              formValues.ybSoftwareVersion !== currentVersion ? (
                <YBCheckBox
                  label="Confirm software upgrade"
                  input={{
                    checked: this.state.formConfirmed,
                    onChange: this.toggleConfirmValidation
                  }}
                />
              ) : (
                <span>Latest software is installed</span>
              )
            }
            asyncValidating={!this.state.formConfirmed}
          >
            <Col lg={12} className="form-section-title">
              Software Package Version
            </Col>
            <div className="form-right-aligned-labels rolling-upgrade-form">
              <Field name="rollingUpgrade" component={YBToggle} label="Rolling Upgrade" />
              <Field
                name="timeDelay"
                type="number"
                component={YBInputField}
                label="Upgrade Delay Between Servers (secs)"
              />
              <Field
                name="ybSoftwareVersion"
                type="select"
                component={YBSelectWithLabel}
                options={softwareVersionOptions}
                label="Server Version"
              />
            </div>
            {errorAlert}
          </YBModal>
        );
      }
      case 'gFlagsModal': {
        return (
          <YBModal
            className={getPromiseState(universe.rollingUpgrade).isError() ? 'modal-shake' : ''}
            visible={modalVisible}
            formName="RollingUpgradeForm"
            onHide={this.resetAndClose}
            footerAccessory={
              formValues.upgradeOption === 'Non-Rolling' && (
                <span className="non-rolling-msg">
                  <img alt="Note" src={WarningIcon} />
                  &nbsp; <b>Note!</b> &nbsp; Flags that require rolling restart won't be applied
                </span>
              )
            }
            title="G-Flags"
            size="large"
            onFormSubmit={submitAction}
            error={error}
            dialogClassName={modalVisible ? 'gflag-modal modal-fade in' : 'modal-fade'}
            showCancelButton={true}
            submitLabel="Apply Changes"
            cancelLabel="Cancel"
          >
            <div className="gflag-modal-body">
              <FieldArray
                name="gFlags"
                component={GFlagComponent}
                dbVersion={currentVersion}
                rerenderOnEveryChange={true}
                editMode={true}
              />
              <FlexContainer className="gflag-upgrade-container">
                <FlexShrink className="gflag-upgrade--label">
                  <span>G-Flag Upgrade Options</span>
                </FlexShrink>
                <div className="gflag-upgrade-options">
                  {['Rolling', 'Non-Rolling', 'Non-Restart'].map((target, i) => (
                    <div key={target} className="row-flex">
                      <div className={clsx('upgrade-radio-option', i === 1 && 'mb-8')} key={target}>
                        <Field
                          name={'upgradeOption'}
                          type="radio"
                          component="input"
                          value={`${target}`}
                        />
                        <span className="upgrade-radio-label">{`${target}`}</span>
                      </div>
                      {i === 0 && (
                        <div className="gflag-delay">
                          <span className="vr-line">|</span>
                          Delay Between Servers :{' '}
                          <Field
                            name="timeDelay"
                            type="number"
                            component={YBInputField}
                            isReadOnly={formValues.upgradeOption !== 'Rolling'}
                          />
                          seconds
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              </FlexContainer>
              {errorAlert}
            </div>
          </YBModal>
        );
      }
      case 'tlsConfigurationModal': {
        if (this.props.enableNewEncryptionInTransitModal) {
          return (
            <EncryptionInTransit
              visible={modalVisible}
              onHide={this.resetAndClose}
              currentUniverse={universe.currentUniverse}
              fetchCurrentUniverse={this.props.fetchCurrentUniverse}
            />
          );
        }
        return (
          <YBModal
            className={getPromiseState(universe.rollingUpgrade).isError() ? 'modal-shake' : ''}
            visible={modalVisible}
            formName="RollingUpgradeForm"
            onHide={this.resetAndClose}
            submitLabel="Upgrade"
            showCancelButton
            title="Edit TLS Configuration"
            onFormSubmit={submitAction}
            error={error}
            footerAccessory={
              formValues.tlsCertificate !==
              universe.currentUniverse?.data?.universeDetails?.rootCA ? (
                <YBCheckBox
                  label="Confirm TLS Changes"
                  input={{
                    checked: this.state.formConfirmed,
                    onChange: this.toggleConfirmValidation
                  }}
                />
              ) : (
                <span>Select new CA signed cert from the list</span>
              )
            }
            asyncValidating={
              !this.state.formConfirmed ||
              formValues.tlsCertificate === universe.currentUniverse?.data?.universeDetails?.rootCA
            }
          >
            <div className="form-right-aligned-labels rolling-upgrade-form">
              <Field
                name="tlsCertificate"
                type="select"
                component={YBSelectWithLabel}
                options={tlsCertificateOptions}
                label="New TLS Certificate"
              />
              <Field
                name="timeDelay"
                type="number"
                required
                component={YBInputField}
                label="Upgrade Delay Between Servers (secs)"
              />
              <Field name="rollingUpgrade" component={YBToggle} label="Rolling Upgrade" />
            </div>
            {errorAlert}
          </YBModal>
        );
      }
      case 'rollingRestart': {
        return (
          <YBModal
            className={getPromiseState(universe.rollingUpgrade).isError() ? 'modal-shake' : ''}
            visible={modalVisible}
            formName="RollingUpgradeForm"
            onHide={this.resetAndClose}
            submitLabel="Restart"
            showCancelButton
            title="Initiate Rolling Restart"
            onFormSubmit={submitAction}
            error={error}
            footerAccessory={
              <YBCheckBox
                label="Confirm rolling restart"
                input={{
                  checked: this.state.formConfirmed,
                  onChange: this.toggleConfirmValidation
                }}
              />
            }
            asyncValidating={!this.state.formConfirmed}
          >
            <div className="form-right-aligned-labels rolling-upgrade-form">
              <Field
                name="timeDelay"
                type="number"
                component={YBInputField}
                label="Rolling Restart Delay Between Servers (secs)"
              />
            </div>
            {errorAlert}
          </YBModal>
        );
      }
      case 'systemdUpgrade': {
        return (
          <YBModal
            className={getPromiseState(universe.rollingUpgrade).isError() ? 'modal-shake' : ''}
            visible={modalVisible}
            formName="RollingUpgradeForm"
            onHide={this.resetAndClose}
            submitLabel="Upgrade"
            showCancelButton
            title="Upgrade from Cron to Systemd"
            onFormSubmit={submitAction}
            error={error}
            footerAccessory={
              formValues.systemdValue !== true ? (
                <YBCheckBox
                  label="Confirm Systemd upgrade"
                  input={{
                    checked: this.state.formConfirmed,
                    onChange: this.toggleConfirmValidation
                  }}
                />
              ) : (
                <span>Already upgraded to Systemd</span>
              )
            }
            asyncValidating={!this.state.formConfirmed}
          >
            <div className="form-right-aligned-labels rolling-upgrade-form">
              <Field
                name="timeDelay"
                type="number"
                component={YBInputField}
                label="Rolling Restart Delay Between Servers (secs)"
              />
            </div>
            {errorAlert}
          </YBModal>
        );
      }
      case 'resizeNodesModal': {
        return (
          <YBModal
            className={getPromiseState(universe.rollingUpgrade).isError() ? 'modal-shake' : ''}
            visible={modalVisible}
            formName="RollingUpgradeForm"
            showCancelButton
            onHide={this.resetAndClose}
            title="Confirm Resize Nodes"
            onFormSubmit={submitAction}
            error={error}
          >
            <div className="form-right-aligned-labels rolling-upgrade-form top-10 time-delay-container">
              <Field
                name="timeDelay"
                type="number"
                component={YBInputField}
                label="Rolling Upgrade Delay Between Servers (secs)"
              />
            </div>
            {errorAlert}
          </YBModal>
        );
      }
      default: {
        return null;
      }
    }
  }
}
