package com.orctom.pipeline.sample.spring.c;

import com.orctom.pipeline.annotation.Actor;
import com.orctom.pipeline.precedure.Outlet;
import com.orctom.pipeline.sample.spring.service.DummyService;
import com.orctom.rmq.Ack;
import com.orctom.rmq.Message;

import javax.annotation.Resource;

@Actor(role = "roleC2", interestedRoles = "roleB2")
class RoleC2 extends Outlet {

  @Resource
  private DummyService service;

  @Override
  protected Ack onMessage(Message message) {
//    System.out.println(service.foo());
//    System.out.println(message);
    return Ack.DONE;
  }
}