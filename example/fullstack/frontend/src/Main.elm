port module Main exposing (main)

{-| The todo UI. A human clicks; an agent sends the same messages.

Agent commands arrive on the `agentCommand` port (from app.js, which
registers the UI tools with autonomous). Each becomes an ordinary `Msg` and
goes through `update`, exactly the path a click takes; the answer, the UI
state the human now sees, leaves on `agentReply`.

The todos themselves live in the Rust backend: the UI reads them over REST
and follows changes, by humans or agents, on a server-sent event stream.

-}

import Browser
import Html exposing (Html, button, div, footer, h1, header, input, label, li, p, section, span, text, ul)
import Html.Attributes exposing (autofocus, checked, class, classList, placeholder, type_, value)
import Html.Events exposing (onCheck, onClick, onInput, onSubmit)
import Http
import Json.Decode as D
import Json.Encode as E


port serverEvent : (D.Value -> msg) -> Sub msg


port agentCommand : (D.Value -> msg) -> Sub msg


port agentReply : E.Value -> Cmd msg



-- MODEL


type alias Todo =
    { id : Int, text : String, done : Bool }


type Filter
    = All
    | Active
    | Done


type alias Activity =
    { who : String, what : String }


type alias Model =
    { todos : List Todo
    , filter : Filter
    , draft : String
    , activity : List Activity
    , error : Maybe String
    }


init : () -> ( Model, Cmd Msg )
init _ =
    ( { todos = [], filter = All, draft = "", activity = [], error = Nothing }
    , fetchTodos
    )



-- UPDATE


type Msg
    = GotTodos (Result Http.Error (List Todo))
    | Saved (Result Http.Error ())
    | DraftChanged String
    | Submit
    | Toggle Int
    | Remove Int
    | ClearDone
    | SetFilter Filter
    | ServerEvent D.Value
    | AgentCommand D.Value


update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
    case msg of
        GotTodos (Ok todos) ->
            ( { model | todos = todos, error = Nothing }, Cmd.none )

        GotTodos (Err _) ->
            ( { model | error = Just "the backend did not answer" }, Cmd.none )

        Saved (Ok ()) ->
            ( model, Cmd.none )

        Saved (Err _) ->
            ( { model | error = Just "the backend refused that" }, Cmd.none )

        DraftChanged s ->
            ( { model | draft = s }, Cmd.none )

        Submit ->
            if String.isEmpty (String.trim model.draft) then
                ( model, Cmd.none )

            else
                ( { model | draft = "" }, post "/api/todos" (E.object [ ( "text", E.string (String.trim model.draft) ) ]) )

        Toggle id ->
            ( model, post ("/api/todos/" ++ String.fromInt id ++ "/toggle") E.null )

        Remove id ->
            ( model, delete ("/api/todos/" ++ String.fromInt id) )

        ClearDone ->
            ( model, post "/api/todos/clear-done" E.null )

        SetFilter f ->
            ( { model | filter = f }, Cmd.none )

        ServerEvent v ->
            case D.decodeValue eventDecoder v of
                Ok ( todos, act ) ->
                    ( { model | todos = todos, activity = List.take 8 (act :: model.activity) }, Cmd.none )

                Err _ ->
                    ( model, Cmd.none )

        AgentCommand v ->
            agentCommandUpdate v model


{-| An agent's UI command: decode it to the Msg a human would cause, run it
through `update`, and answer with what the human now sees.
-}
agentCommandUpdate : D.Value -> Model -> ( Model, Cmd Msg )
agentCommandUpdate v model =
    case D.decodeValue commandDecoder v of
        Ok ( id, Just inner ) ->
            let
                ( m2, cmd ) =
                    update inner model
            in
            ( m2, Cmd.batch [ cmd, agentReply (E.object [ ( "id", E.int id ), ( "ok", uiState m2 ) ]) ] )

        Ok ( id, Nothing ) ->
            ( model, agentReply (E.object [ ( "id", E.int id ), ( "ok", uiState model ) ]) )

        Err e ->
            ( model
            , agentReply
                (E.object
                    [ ( "id", E.int (Result.withDefault -1 (D.decodeValue (D.field "id" D.int) v)) )
                    , ( "error", E.string (D.errorToString e) )
                    ]
                )
            )


{-| (request id, the Msg it stands for; Nothing for a pure read).
-}
commandDecoder : D.Decoder ( Int, Maybe Msg )
commandDecoder =
    D.map2 Tuple.pair
        (D.field "id" D.int)
        (D.field "tool" D.string
            |> D.andThen
                (\tool ->
                    case tool of
                        "state" ->
                            D.succeed Nothing

                        "set_filter" ->
                            D.at [ "args", "filter" ] filterDecoder |> D.map (SetFilter >> Just)

                        "type" ->
                            D.at [ "args", "text" ] D.string |> D.map (DraftChanged >> Just)

                        "submit" ->
                            D.succeed (Just Submit)

                        "click_toggle" ->
                            D.at [ "args", "id" ] D.int |> D.map (Toggle >> Just)

                        "click_remove" ->
                            D.at [ "args", "id" ] D.int |> D.map (Remove >> Just)

                        "click_clear_done" ->
                            D.succeed (Just ClearDone)

                        other ->
                            D.fail ("no UI command " ++ other)
                )
        )


filterDecoder : D.Decoder Filter
filterDecoder =
    D.string
        |> D.andThen
            (\s ->
                case s of
                    "all" ->
                        D.succeed All

                    "active" ->
                        D.succeed Active

                    "done" ->
                        D.succeed Done

                    _ ->
                        D.fail ("unknown filter " ++ s)
            )


filterName : Filter -> String
filterName f =
    case f of
        All ->
            "all"

        Active ->
            "active"

        Done ->
            "done"


visible : Model -> List Todo
visible model =
    List.filter
        (\t ->
            case model.filter of
                All ->
                    True

                Active ->
                    not t.done

                Done ->
                    t.done
        )
        model.todos


{-| What the human sees, as an agent reads it.
-}
uiState : Model -> E.Value
uiState model =
    E.object
        [ ( "filter", E.string (filterName model.filter) )
        , ( "draft", E.string model.draft )
        , ( "visible", E.list encodeTodo (visible model) )
        , ( "left", E.int (List.length (List.filter (not << .done) model.todos)) )
        ]



-- HTTP


fetchTodos : Cmd Msg
fetchTodos =
    Http.get { url = "/api/todos", expect = Http.expectJson GotTodos (D.list todoDecoder) }


post : String -> E.Value -> Cmd Msg
post url body =
    Http.post { url = url, body = Http.jsonBody body, expect = Http.expectWhatever Saved }


delete : String -> Cmd Msg
delete url =
    Http.request
        { method = "DELETE"
        , headers = []
        , url = url
        , body = Http.emptyBody
        , expect = Http.expectWhatever Saved
        , timeout = Nothing
        , tracker = Nothing
        }


todoDecoder : D.Decoder Todo
todoDecoder =
    D.map3 Todo (D.field "id" D.int) (D.field "text" D.string) (D.field "done" D.bool)


encodeTodo : Todo -> E.Value
encodeTodo t =
    E.object [ ( "id", E.int t.id ), ( "text", E.string t.text ), ( "done", E.bool t.done ) ]


{-| A backend event: the todos after the change, and who did what.
-}
eventDecoder : D.Decoder ( List Todo, Activity )
eventDecoder =
    D.map2 Tuple.pair
        (D.field "todos" (D.list todoDecoder))
        (D.map2 Activity (D.field "by" D.string) (D.field "what" D.string))



-- VIEW


view : Model -> Html Msg
view model =
    div [ class "app" ]
        [ header []
            [ h1 [] [ text "todos" ]
            , p [ class "sub" ] [ text "Elm UI · Rust backend · driven by humans and agents through autonomous" ]
            ]
        , Html.form [ onSubmit Submit ]
            [ input [ placeholder "What needs doing?", value model.draft, onInput DraftChanged, autofocus True ] [] ]
        , section [ class "filters" ] (List.map (filterButton model.filter) [ All, Active, Done ])
        , ul [ class "todos" ] (List.map viewTodo (visible model))
        , footer []
            [ span [] [ text (String.fromInt (List.length (List.filter (not << .done) model.todos)) ++ " left") ]
            , button [ onClick ClearDone ] [ text "Clear done" ]
            ]
        , case model.error of
            Just e ->
                p [ class "error" ] [ text e ]

            Nothing ->
                text ""
        , section [ class "activity" ]
            [ h1 [] [ text "activity" ]
            , ul [] (List.map (\a -> li [ classList [ ( "agent", a.who /= "human" ) ] ] [ span [ class "who" ] [ text a.who ], text (" " ++ a.what) ]) model.activity)
            ]
        ]


filterButton : Filter -> Filter -> Html Msg
filterButton current f =
    button [ classList [ ( "on", current == f ) ], onClick (SetFilter f) ] [ text (filterName f) ]


viewTodo : Todo -> Html Msg
viewTodo t =
    li [ classList [ ( "done", t.done ) ] ]
        [ label []
            [ input [ type_ "checkbox", checked t.done, onCheck (\_ -> Toggle t.id) ] []
            , text t.text
            ]
        , button [ class "remove", onClick (Remove t.id) ] [ text "×" ]
        ]



-- MAIN


main : Program () Model Msg
main =
    Browser.element
        { init = init
        , update = update
        , view = view
        , subscriptions = \_ -> Sub.batch [ serverEvent ServerEvent, agentCommand AgentCommand ]
        }
