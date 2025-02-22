/*
 * Created on Fri Feb 18 2022
 *
 * Copyright 2021 YugaByte, Inc. and Contributors
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License")
 * You may not use this file except in compliance with the License. You may obtain a copy of the License at
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

import { isFunction } from 'lodash';
import moment from 'moment';
import React, { FC } from 'react';
import { useState } from 'react';
import { useSelector } from 'react-redux';
import { Backup_States } from '.';
import { YBControlledTextInput } from '../common/forms/fields';
import './BackupUtils.scss';

/**
 * Calculates the difference between two dates
 * @param startTime start time
 * @param endtime end time
 * @returns diff between the dates
 */
export const calculateDuration = (startTime: number, endtime: number): string => {
  const start = moment(startTime);
  const end = moment(endtime);
  const totalDays = end.diff(start, 'days');
  const totalHours = end.diff(start, 'hours');
  const totalMinutes = end.diff(start, 'minutes');
  const totalSeconds = end.diff(start, 'seconds');
  let duration = totalDays !== 0 ? `${totalDays} d ` : '';
  duration += totalHours % 24 !== 0 ? `${totalHours % 24} h ` : '';
  duration += totalMinutes % 60 !== 0 ? `${totalMinutes % 60} m ` : ``;
  duration += totalSeconds % 60 !== 0 ? `${totalSeconds % 60} s` : '';
  return duration;
};

export const BACKUP_STATUS_OPTIONS: { value: Backup_States | null; label: string }[] = [
  {
    label: 'All',
    value: null
  },
  {
    label: 'In Progress',
    value: Backup_States.IN_PROGRESS
  },
  {
    label: 'Completed',
    value: Backup_States.COMPLETED
  },
  {
    label: 'Delete In Progress',
    value: Backup_States.DELETE_IN_PROGRESS
  },
  {
    label: 'Deleted',
    value: Backup_States.DELETED
  },
  {
    label: 'Failed',
    value: Backup_States.FAILED
  },
  {
    label: 'Failed To Delete',
    value: Backup_States.FAILED_TO_DELETE
  },
  {
    label: 'Queued For Deletion',
    value: Backup_States.QUEUED_FOR_DELETION
  },
  {
    label: 'Skipped',
    value: Backup_States.SKIPPED
  },
  {
    label: 'Cancelled',
    value: Backup_States.STOPPED
  }
];

export const DATE_FORMAT = 'YYYY-MM-DD HH:mm:ss';
export const KEYSPACE_VALIDATION_REGEX = /^[A-Za-z_][A-Za-z_0-9$]*$/;

export const formatUnixTimeStamp = (unixTimeStamp: number) =>
  moment(unixTimeStamp).format(DATE_FORMAT);

export const RevealBadge = ({ label, textToShow }: { label: string; textToShow: string }) => {
  const [reveal, setReveal] = useState(false);
  return (
    <span className="reveal-badge">
      {reveal ? (
        <span onClick={() => setReveal(false)}>{textToShow}</span>
      ) : (
        <span onClick={() => setReveal(true)}>{label}</span>
      )}
    </span>
  );
};

export const FormatUnixTimeStampTimeToTimezone = ({ timestamp }: { timestamp: any }) => {
  const currentUserTimezone = useSelector((state: any) => state.customer.currentUser.data.timezone);
  if (!timestamp) return <span>-</span>;
  const formatTime = (currentUserTimezone
    ? (moment.utc(timestamp) as any).tz(currentUserTimezone)
    : moment.utc(timestamp)
  ).format('YYYY-MM-DD H:mm:ss');
  return <span>{formatTime}</span>;
};

export const SearchInput: FC<any> = (props) => {
  return (
    <div className="search-input">
      <i className="fa fa-search" />
      <YBControlledTextInput
        {...props}
        input={{
          onKeyUp: (e: any) =>
            e.key === 'Enter' &&
            isFunction(props.onEnterPressed) &&
            props.onEnterPressed(e.currentTarget.value)
        }}
      />
    </div>
  );
};

export const ENTITY_NOT_AVAILABLE = (
  <span className="alert-message warning">
    Not Available <i className="fa fa-warning" />
  </span>
);
export const SPINNER_ICON = <i className="fa fa-spinner fa-pulse" />;

export const CALDENDAR_ICON = () => ({
  alignItems: 'center',
  display: 'flex',

  ':before': {
    backgroundColor: 'white',
    borderRadius: 10,
    fontFamily: 'FontAwesome',
    content: '"\f133"',
    display: 'block',
    marginRight: 8
  }
});
