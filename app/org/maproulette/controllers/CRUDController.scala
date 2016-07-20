// Copyright (C) 2016 MapRoulette contributors (see CONTRIBUTORS.md).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package org.maproulette.controllers

import java.sql.Connection

import com.fasterxml.jackson.databind.JsonMappingException
import io.swagger.annotations._
import org.maproulette.actions.{Created => ActionCreated, _}
import org.maproulette.models.BaseObject
import org.maproulette.models.dal.BaseDAL
import org.maproulette.exception.{MPExceptionUtil, NotFoundException, StatusMessage, StatusMessages}
import org.maproulette.metrics.Metrics
import org.maproulette.session.{SessionManager, User}
import org.maproulette.utils.Utils
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{Action, AnyContent, BodyParsers, Controller}

/**
  * This is the base controller class that handles all the CRUD operations for the objects in Map Roulette.
  * This includes creation, reading, updating and deleting. It also includes a standard list function
  * and batch upload process.
  *
  * @author cuthbertm
  */
trait CRUDController[T<:BaseObject[Long]] extends Controller with DefaultWrites with StatusMessages {
  // Data access layer that has to be instantiated by the class that mixes in the trait
  protected val dal:BaseDAL[Long, T]
  // The default reads that allows the class to read the json from a posted json body
  implicit val tReads:Reads[T]
  // The default writes that allows the class to write the object as json to a response body
  implicit val tWrites:Writes[T]
  // the type of object that the controller is executing against
  implicit val itemType:ItemType
  // The session manager which should be injected into the implementing class using @Inject
  val sessionManager:SessionManager
  // The action manager which should be injected into the implementing class using @Inject
  val actionManager:ActionManager

  /**
    * Function can be implemented to extract more information than just the default create data,
    * to build other objects with the current object at the core. No data will be returned from this
    * function, it purely does work in the background AFTER creating the current object
    *
    * @param body The Json body of data
    * @param createdObject The object that was created by the create function
    * @param user The user that is executing the function
    */
  def extractAndCreate(body:JsValue, createdObject:T, user:User)(implicit c:Option[Connection]=None) : Unit = { }

  /**
    * This function allows sub classes to modify the body, primarily this would be used for inserting
    * default elements into the body that shouldn't have to be required to create an object.
    *
    * @param body The incoming body from the request
    * @param user The user executing the request
    * @return
    */
  def updateCreateBody(body:JsValue, user:User) : JsValue = Utils.insertJsonID(body)

  /**
    * In the case where you need to update the update body, usually you would not update it, but
    * just in case.
    *
    * @param body The request body
    * @param user The user executing the request
    * @return The updated request body
    */
  def updateUpdateBody(body:JsValue, user:User) : JsValue = body

  // scalastyle:off
  /**
    * The base create function that most controllers will run through to create the object. The
    * actual work will be passed on to the internalCreate function. This is so that if you want
    * to create your object differently you can keep the standard http functionality around and
    * not have to reproduce a lot of the work done in this function. If the id is supplied in the
    * json body it will pass off the workload to the update function. If no id is supplied then it
    * will check the name to see if it can find any items in the database that match the name.
    * Must be authenticated to perform operation
    *
    * @return 201 Created with the json body of the created object
    */
  @ApiOperation(
    nickname = "create",
    value = "Create object from json body payload",
    notes =
      """The create method will assume that the json payload contains all the required
         parameters for the object it wishes to create. If the 'id' variable is supplied it will
         rather look up the object and then update it instead. If the 'id' is supplied but no object
         matching it is found, a 404 NotFound will be returned.""",
    httpMethod = "POST",
    produces = "application/json",
    consumes = "application/json",
    protocols = "http",
    code = 200
  )
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      name = "apiKey", value = "The apikey to authorize the request", required = true, dataType = "string", paramType = "header"
    ),
    new ApiImplicitParam(
      name = "body", value = "The json payload containing updates", required = true, dataType = "string", paramType = "body"
    )
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 304, message = "Not updated responding with empty payload if object already exists and nothing to update"),
    new ApiResponse(code = 400, message = "Invalid json payload for object", response = classOf[StatusMessage]),
    new ApiResponse(code = 404, message = "ID field supplied but no object found matching the id", response = classOf[StatusMessage])
  ))
  // scalastyle:on
  def create() : Action[JsValue] = Action.async(BodyParsers.parse.json) { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val result = this.updateCreateBody(request.body, user).validate[T]
      result.fold(
        errors => {
          BadRequest(Json.toJson(StatusMessage("KO", JsError.toJson(errors))))
        },
        element => {
          MPExceptionUtil.internalExceptionCatcher { () =>
            if (element.id > -1) {
              // if you provide the ID in the post method we will send you to the update path
              this.internalUpdate(request.body, user)(element.id.toString, -1) match {
                case Some(value) => Ok(Json.toJson(value))
                case None => NotModified
              }
            } else {
              this.internalCreate(request.body, element, user) match {
                case Some(value) => Created(Json.toJson(value))
                case None => NotModified
              }
            }
          }
        }
      )
    }
  }

  /**
    * Calls the insert function from the data access layer for the particular object. It will also
    * call the extractAndCreate function after the insert, which by default does nothing. The ParentController
    * will use that function to create any children of the parent. Will also create a "create" action
    * in the database
    *
    * @param requestBody The request body containing the full json payload
    * @param element The intial object to be created. Ie. if this was the ProjectController then it would be a project object
    * @param user The user that is executing this request
    * @return The createdObject (not any of it's children if creating multiple objects, only top level)
    */
  def internalCreate(requestBody:JsValue, element:T, user:User)(implicit c:Option[Connection]=None) : Option[T] = {
    this.dal.mergeUpdate(element, user)(element.id) match {
      case Some(created) =>
        this.extractAndCreate(requestBody, created, user)
        this.actionManager.setAction(Some(user), this.itemType.convertToItem(created.id), ActionCreated(), "")
        Some(created)
      case None => None
    }
  }

  /**
    * Passes functionality to the extractAndCreate function if the updatedObject is not None,
    * otherwise will do nothing by default
    *
    * @param body The request body containing the full json payload
    * @param updatedObject The object that was updated.
    * @param user The user executing the operation
    */
  def extractAndUpdate(body:JsValue, updatedObject:Option[T], user:User) : Unit = {
    updatedObject match {
      case Some(updated) => this.extractAndCreate(body, updated, user)
      case None => // ignore
    }
  }

  // scalastyle:off
  /**
    * Base update function for the object. The update function works very similarly to the create
    * function. It does however allow the user to supply only the elements that are needed to updated.
    * Must be authenticated to perform operation
    *
    * @param id The id for the object
    * @return 200 OK with the updated object, 304 NotModified if not updated
    */
  @ApiOperation(
    nickname = "update",
    value = "Create object from json body payload",
    notes =
      """The update method will update any variables that are supplied in the json payload""",
    httpMethod = "PUT",
    produces = "application/json",
    consumes = "application/json",
    protocols = "http",
    code = 200
  )
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      name = "apiKey", value = "The apikey to authorize the request", required = true, dataType = "string", paramType = "header"
    ),
    new ApiImplicitParam(
      name = "body", value = "The json payload containing updates", required = true, dataType = "string", paramType = "body"
    )
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 304, message = "Not updated responding with empty payload if object already exists and nothing to update"),
    new ApiResponse(code = 400, message = "Invalid json payload for object", response = classOf[StatusMessage]),
    new ApiResponse(code = 404, message = "ID field supplied but no object found matching the id", response = classOf[StatusMessage])
  ))
  // scalastyle:on
  def update(implicit id:Long) : Action[JsValue] = Action.async(BodyParsers.parse.json) { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      try {
        this.internalUpdate(updateUpdateBody(request.body, user), user)(id.toString, -1) match {
          case Some(value) => Ok(Json.toJson(value))
          case None =>  throw new NotFoundException(s"No object found with id [$id]")
        }
      } catch {
        case e:JsonMappingException =>
          Logger.error(e.getMessage, e)
          BadRequest(Json.toJson(StatusMessage("KO", JsString(e.getMessage))))
      }
    }
  }

  /**
    * The function that does the actual update of the object, it will pass of the updatedObject to
    * the extractAndUpdate function after the object has been updated.
    *
    * @param requestBody The full request body payload pass in the request
    * @param user The user executing the request
    * @param id The id of the object being updated
    * @return The object that was updated, None if it was not updated.
    */
  def internalUpdate(requestBody:JsValue, user:User)(implicit id:String, parentId:Long, c:Option[Connection]=None) : Option[T] = {
    val updatedObject = if (Utils.isDigit(id)) {
      this.dal.update(requestBody, user)(id.toLong)
    } else {
      this.dal.updateByName(requestBody, user)
    }
    this.extractAndUpdate(requestBody, updatedObject, user)
    updatedObject match {
      case Some(obj) => this.actionManager.setAction(Some(user), this.itemType.convertToItem(obj.id), Updated(), "")
      case None => //ignore
    }
    updatedObject
  }

  /**
    * Retrieves the object from the database or primary storage and writes it as json as a response.
    *
    * @param id The id of the object that is being retrieved
    * @return 200 Ok, 204 NoContent if not found
    */
  def read(implicit id:Long) : Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      this.dal.retrieveById match {
        case Some(value) => Ok(Json.toJson(value))
        case None => NoContent
      }
    }
  }

  /**
    * Given the name of the object and the id of the objects parent, the object will be retrieved
    * from the database and returned to the user in JSON form
    *
    * @param id The id of the parent of the object
    * @param name The name of the object
    * @return 200 Ok, 204 NoContent if not found
    */
  def readByName(id:Long, name:String) : Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      this.dal.retrieveByName(name, id) match {
        case Some(value) => Ok(Json.toJson(value))
        case None => NoContent
      }
    }
  }

  /**
    * Deletes an object from the database or primary storage.
    * Must be authenticated to perform operation
    *
    * @param id The id of the object to delete
    * @return 204 NoContent
    */
  def delete(id:Long) : Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      this.dal.delete(id.toLong, user)
      this.actionManager.setAction(Some(user), this.itemType.convertToItem(id.toLong), Deleted(), "")
      Ok(Json.toJson(StatusMessage("OK",
        JsString(s"${Actions.getTypeName(this.itemType.typeId).getOrElse("Unknown Object")} $id deleted by user ${user.id}."))))
    }
  }

  /**
    * Simply lists the objects for the current controller. This can be an especially expensive operation,
    * as if you just list all tasks it will try to list all of the tasks, which could be over 100,000
    * which could cause OOM's but also cause issues with the browser trying to displaying all the data.
    *
    * TODO: This function probably should force authentication, but also use streaming. Possibly
    * only allow super users to do it.
    *
    * @param limit limit the number of objects returned in the list
    * @param offset For paging, if limit is 10, total 100, then offset 1 will return items 11 - 20
    * @return A list of requested objects
    */
  def list(limit:Int, offset:Int) : Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      val result = this.dal.list(limit, offset)
      this.itemType match {
        case it:TaskType if user.isDefined =>
          result.foreach(task => this.actionManager.setAction(user, this.itemType.convertToItem(task.id), TaskViewed(), ""))
        case _ => //ignore, only update view actions if it is a task type
      }
      Ok(Json.toJson(result))
    }
  }

  /**
    * Helper function that does a batch upload and only creates new object, does not update existing ones
    * Must be authenticated to perform operation
    *
    * @return 200 OK basic message saying all items where uploaded
    */
  def batchUploadPost : Action[JsValue] = this.batchUpload(false)

  /**
    * Helper function that does a batch upload and creates new objects, and updates existing ones.
    * Must be authenticated to perform operation
    *
    * @return 200 OK basic message saying all items where uploaded
    */
  def batchUploadPut : Action[JsValue] = this.batchUpload(true)

  /**
    * Allows a basic upload batch process of the items from the json payload. This is also leveraged
    * when creating children objects at the same time as the parent.
    * Must be authenticated to perform operation
    *
    * @param update Whether to update any objects found matching in the database
    * @return 200 OK basic message saying all items where uploaded
    */
  def batchUpload(update:Boolean) : Action[JsValue] = Action.async(BodyParsers.parse.json) { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      request.body.validate[List[JsValue]].fold(
        errors => {
          BadRequest(Json.toJson(StatusMessage("KO", JsError.toJson(errors))))
        },
        items => {
          this.internalBatchUpload(request.body, items, user, update)
          Ok(Json.toJson(StatusMessage("OK", JsString("Items created and updated"))))
        }
      )
    }
  }

  /**
    * Internal method that is used to actually execute the batch upload
    *
    * @param requestBody The full request body
    * @param arr The array of json objects representing the objects or values of those objects that you want to update/create
    * @param user The user executing the request
    * @param update Whether to update the object if a matching object is found, if false will simply do nothing
    */
  def internalBatchUpload(requestBody:JsValue, arr:List[JsValue], user:User, update:Boolean=false) : Unit = {
    this.dal.getDatabase.withTransaction { implicit c =>
      Metrics.timer("BatchUpload LOOP") { () =>
        arr.foreach(element => (element \ "id").asOpt[String] match {
          case Some(itemID) => if (update) this.internalUpdate(element, user)(itemID, -1)
          case None =>
            this.updateCreateBody(element, user).validate[T].fold(
              errors => Logger.warn(s"Invalid json for type: ${JsError.toJson(errors).toString}"),
              validT => this.internalCreate(element, validT, user)
            )
        })
      }
    }
  }

  /**
    * Does a basic search on the name of an object
    *
    * @param search The search string that we are looking for
    * @param limit limit the number of returned items
    * @param offset For paging, if limit is 10, total 100, then offset 1 will return items 11 - 20
    * @param onlyEnabled only enabled objects if true
    * @return A list of the requested items in JSON format
    */
  def find(search:String, parentId:Long, limit:Int, offset:Int, onlyEnabled:Boolean) : Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      Ok(Json.toJson(this.dal.find(search, limit, offset, onlyEnabled, "name", "DESC")(parentId)))
    }
  }
}
