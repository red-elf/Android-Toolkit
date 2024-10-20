package com.redelf.commons.connectivity.indicator.stateful

import com.redelf.commons.connectivity.indicator.AvailableService
import com.redelf.commons.registration.Registration
import com.redelf.commons.stateful.Stateful

interface AvailableStatefulService<T> : AvailableService, Stateful<T>, Registration<Stateful<T>>