// Copyright (c) YugaByte, Inc.

import React, { Component } from 'react';
import { PageHeader } from 'react-bootstrap';
import { YBButton } from '../fields';
import { YBLabel } from '../../descriptors';
import { getPromiseState } from '../../../../utils/PromiseUtils';
import YBLogo from '../../YBLogo/YBLogo';
import { browserHistory } from 'react-router';
import { Field, Form, Formik } from 'formik';
import * as Yup from 'yup';
import _ from 'lodash';
import { ROOT_URL, USE_SSO } from '../../../../config';
import { clearCredentials } from '../../../../routes';
import { trimString } from '../../../../utils/ObjectUtils';

class LoginForm extends Component {
  constructor(props) {
    super(props);
    clearCredentials();
  }

  submitLogin = (formValues) => {
    const { loginCustomer } = this.props;
    formValues.email = trimString(formValues.email);
    loginCustomer(formValues);
  };

  componentDidUpdate(prevProps) {
    if (USE_SSO) return;

    const {
      customer: { authToken, error }
    } = this.props;
    const currentAuth = prevProps.customer.authToken;
    if (getPromiseState(authToken).isSuccess() && !_.isEqual(authToken, currentAuth)) {
      if (error === 'Invalid') {
        this.props.resetCustomerError();
        browserHistory.goBack();
      } else {
        if (localStorage.getItem('__yb_intro_dialog__') !== 'hidden') {
          localStorage.setItem('__yb_intro_dialog__', 'new');
        }
        browserHistory.push('/');
      }
    }
  }

  runSSO() {
    if (localStorage.getItem('__yb_intro_dialog__') !== 'hidden') {
      localStorage.setItem('__yb_intro_dialog__', 'new');
    }
    window.location.replace(`${ROOT_URL}/third_party_login`);
  }

  render() {
    const {
      customer: { authToken }
    } = this.props;

    const validationSchema = Yup.object().shape({
      email: Yup.string().required('Enter Email or Username'),

      password: Yup.string().required('Enter password')
    });

    const initialValues = {
      email: '',
      password: ''
    };

    return (
      <div className="container full-height dark-background flex-vertical-middle">
        <div className="col-sm-5 dark-form">
          <PageHeader bsClass="dark-form-heading">
            <YBLogo type="full" />
            <span>Admin Console</span>
          </PageHeader>
          {USE_SSO ? (
            <div>
              <YBButton btnClass="btn btn-orange" btnText="Login with SSO" onClick={this.runSSO} />
            </div>
          ) : (
            <Formik
              validationSchema={validationSchema}
              initialValues={initialValues}
              onSubmit={(values, { setSubmitting }) => {
                this.submitLogin(values);
                setSubmitting(false);
              }}
            >
              {({ handleSubmit, isSubmitting }) => (
                <Form onSubmit={handleSubmit}>
                  <div
                    className={`alert alert-danger form-error-alert ${
                      authToken.error ? '' : 'hide'
                    }`}
                  >
                    {<strong>{JSON.stringify(authToken.error)}</strong>}
                  </div>
                  <div className="clearfix">
                    <Field name="email">
                      {(props) => (
                        <YBLabel {...props} name="email">
                          <input
                            className="form-control"
                            placeholder="Email or Username"
                            type="text"
                            {...props.field}
                          />
                        </YBLabel>
                      )}
                    </Field>
                    <Field name="password">
                      {(props) => (
                        <YBLabel {...props} name="password">
                          <input
                            className="form-control"
                            placeholder="Password"
                            type="password"
                            {...props.field}
                          />
                        </YBLabel>
                      )}
                    </Field>
                  </div>
                  <div className="clearfix">
                    <YBButton
                      btnType="submit"
                      disabled={isSubmitting || getPromiseState(authToken).isLoading()}
                      btnClass="btn btn-orange"
                      btnText="Login"
                    />
                  </div>
                </Form>
              )}
            </Formik>
          )}
        </div>
      </div>
    );
  }
}

export default LoginForm;
